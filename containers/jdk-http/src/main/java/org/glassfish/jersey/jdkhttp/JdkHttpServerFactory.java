/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.jdkhttp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.ws.rs.ProcessingException;

import org.glassfish.jersey.jdkhttp.internal.LocalizationMessages;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.internal.ConfigHelper;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Factory for creating {@link HttpServer JDK HttpServer} instances to run Jersey applications.
 *
 * @author Miroslav Fuksa (miroslav.fuksa at oracle.com)
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
public final class JdkHttpServerFactory {

    /**
     * Create and start the {@link HttpServer JDK HttpServer} with the Jersey application deployed
     * at the given {@link URI}.
     * <p>
     * The returned {@link HttpServer JDK HttpServer} is started.
     * </p>
     *
     * @param uri           the {@link URI uri} on which the Jersey application will be deployed.
     * @param configuration the Jersey server-side application configuration.
     * @return Newly created {@link HttpServer}.
     *
     * @throws ProcessingException thrown when problems during server creation
     *                             occurs.
     */
    public static HttpServer createHttpServer(final URI uri, final ResourceConfig configuration) {
        return createHttpServer(uri, configuration, true);
    }

    /**
     * Create (and possibly start) the {@link HttpServer JDK HttpServer} with the JAX-RS / Jersey application deployed
     * on the given {@link URI}.
     * <p>
     * The {@code start} flag controls whether or not the returned {@link HttpServer JDK HttpServer} is started.
     * </p>
     *
     * @param uri           the {@link URI uri} on which the Jersey application will be deployed.
     * @param configuration the Jersey server-side application configuration.
     * @param start         if set to {@code false}, the created server will not be automatically started.
     * @return Newly created {@link HttpServer}.
     *
     * @throws ProcessingException thrown when problems during server creation occurs.
     * @since 2.8
     */
    public static HttpServer createHttpServer(final URI uri, final ResourceConfig configuration, final boolean start) {
        return createHttpServer(uri, new JdkHttpHandlerContainer(configuration), start);
    }

    private static HttpServer createHttpServer(final URI uri, final JdkHttpHandlerContainer handler, final boolean start) {

        if (uri == null) {
            throw new IllegalArgumentException(LocalizationMessages.ERROR_CONTAINER_URI_NULL());
        }

        final String scheme = uri.getScheme();
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(LocalizationMessages.ERROR_CONTAINER_URI_SCHEME_UNKNOWN(uri));
        }

        final String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException(LocalizationMessages.ERROR_CONTAINER_URI_PATH_NULL(uri));
        } else if (path.isEmpty()) {
            throw new IllegalArgumentException(LocalizationMessages.ERROR_CONTAINER_URI_PATH_EMPTY(uri));
        } else if (path.charAt(0) != '/') {
            throw new IllegalArgumentException(LocalizationMessages.ERROR_CONTAINER_URI_PATH_START(uri));
        }

        final boolean isHttp = scheme.equalsIgnoreCase("http");
        final int port = (uri.getPort() == -1)
                ? (isHttp ? ConfigHelper.DEFAULT_HTTP_PORT : ConfigHelper.DEFAULT_HTTPS_PORT)
                : uri.getPort();

        final HttpServer server;
        try {
            server = isHttp
                    ? HttpServer.create(new InetSocketAddress(port), 0)
                    : HttpsServer.create(new InetSocketAddress(port), 0);
        } catch (final IOException ioe) {
            throw new ProcessingException(LocalizationMessages.ERROR_CONTAINER_EXCEPTION_IO(), ioe);
        }

        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(path, handler);

        final HttpServer wrapper = isHttp
                ? createHttpServerWrapper(server, handler)
                : createHttpsServerWrapper((HttpsServer) server, handler);

        if (start) {
            wrapper.start();
        }

        return wrapper;
    }

    private static HttpServer createHttpsServerWrapper(final HttpsServer delegate, final JdkHttpHandlerContainer handler) {
        return new HttpsServer() {

            @Override
            public void setHttpsConfigurator(final HttpsConfigurator httpsConfigurator) {
                delegate.setHttpsConfigurator(httpsConfigurator);
            }

            @Override
            public HttpsConfigurator getHttpsConfigurator() {
                return delegate.getHttpsConfigurator();
            }

            @Override
            public void bind(final InetSocketAddress inetSocketAddress, final int i) throws IOException {
                delegate.bind(inetSocketAddress, i);
            }

            @Override
            public void start() {
                delegate.start();
                handler.onServerStart();
            }

            @Override
            public void setExecutor(final Executor executor) {
                delegate.setExecutor(executor);
            }

            @Override
            public Executor getExecutor() {
                return delegate.getExecutor();
            }

            @Override
            public void stop(final int i) {
                handler.onServerStop();
                delegate.stop(i);
            }

            @Override
            public HttpContext createContext(final String s, final HttpHandler httpHandler) {
                return delegate.createContext(s, httpHandler);
            }

            @Override
            public HttpContext createContext(final String s) {
                return delegate.createContext(s);
            }

            @Override
            public void removeContext(final String s) throws IllegalArgumentException {
                delegate.removeContext(s);
            }

            @Override
            public void removeContext(final HttpContext httpContext) {
                delegate.removeContext(httpContext);
            }

            @Override
            public InetSocketAddress getAddress() {
                return delegate.getAddress();
            }
        };
    }

    private static HttpServer createHttpServerWrapper(final HttpServer delegate, final JdkHttpHandlerContainer handler) {
        return new HttpServer() {

            @Override
            public void bind(final InetSocketAddress inetSocketAddress, final int i) throws IOException {
                delegate.bind(inetSocketAddress, i);
            }

            @Override
            public void start() {
                delegate.start();
                handler.onServerStart();
            }

            @Override
            public void setExecutor(final Executor executor) {
                delegate.setExecutor(executor);
            }

            @Override
            public Executor getExecutor() {
                return delegate.getExecutor();
            }

            @Override
            public void stop(final int i) {
                handler.onServerStop();
                delegate.stop(i);
            }

            @Override
            public HttpContext createContext(final String s, final HttpHandler httpHandler) {
                return delegate.createContext(s, httpHandler);
            }

            @Override
            public HttpContext createContext(final String s) {
                return delegate.createContext(s);
            }

            @Override
            public void removeContext(final String s) throws IllegalArgumentException {
                delegate.removeContext(s);
            }

            @Override
            public void removeContext(final HttpContext httpContext) {
                delegate.removeContext(httpContext);
            }

            @Override
            public InetSocketAddress getAddress() {
                return delegate.getAddress();
            }
        };
    }

    /**
     * Prevents instantiation.
     */
    private JdkHttpServerFactory() {
    }
}
