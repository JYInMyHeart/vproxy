package net.cassite.vproxy.component.proxy;

import net.cassite.vproxy.connection.*;
import net.cassite.vproxy.protocol.ProtocolConnectionHandler;
import net.cassite.vproxy.protocol.ProtocolHandler;
import net.cassite.vproxy.protocol.ProtocolHandlerContext;
import net.cassite.vproxy.util.*;

import java.io.IOException;
import java.nio.channels.NetworkChannel;
import java.util.Collection;

/**
 * when a connection is accepted, another connection will be generated by calling the callback handler<br>
 * the accepted connection and the new connection form up a {@link Session}<br>
 * the session operations will always be handled in the same event loop
 */
public class Proxy {
    private static void utilValidate(ProxyNetConfig config) {
        if (config.acceptLoop == null)
            throw new IllegalArgumentException("no accept loop");
        if (config.connGen == null)
            throw new IllegalArgumentException("no connection generator");
        if (config.handleLoopProvider == null)
            throw new IllegalArgumentException("no handler loop provider");
        if (config.server == null)
            throw new IllegalArgumentException("no server");
        if (config.inBufferSize <= 0)
            throw new IllegalArgumentException("inBufferSize <= 0");
        if (config.outBufferSize <= 0)
            throw new IllegalArgumentException("outBufferSize <= 0");
    }

    private static void utilCloseConnection(Connection connection) {
        assert Logger.lowLevelDebug("close connection " + connection);
        connection.close();
    }

    private static void utilCloseConnectionAndReleaseBuffers(Connection connection) {
        utilCloseConnection(connection);
        connection.inBuffer.clean();
        connection.outBuffer.clean();
    }

    private static void utilCloseSessionAndReleaseBuffers(Session session) {
        utilCloseConnectionAndReleaseBuffers(session.active);
        utilCloseConnection(session.passive);
    }

    class SessionServerHandler implements ServerHandler {
        @Override
        public void acceptFail(ServerHandlerContext ctx, IOException err) {
            Logger.fatal(LogType.SERVER_ACCEPT_FAIL, "accept connection failed, server = " + config.server + ", err = " + err);
        }

        @Override
        public void connection(ServerHandlerContext ctx, Connection connection) {
            switch (config.connGen.get().type()) {
                case handler:
                    handleHandler(connection);
                    break;
                case direct:
                default:
                    handleDirect(connection);
            }
        }

        private void handleDirect(Connection connection) {
            // make connection to another end point
            Connector connector = config.connGen.get().genConnector(connection);
            handleDirect(connection, connector);
        }

        private void handleDirect(Connection connection, Connector connector) {
            // check whether address tuple is null
            // null means the user code fail to provide a new connection
            // maybe user think that the backend is not working, or the source ip is forbidden
            // any way, the user refuse to provide a new connection
            if (connector == null) {
                Logger.info(LogType.NO_CLIENT_CONN, "the user code refuse to provide a remote endpoint");
                // close the active connection
                utilCloseConnectionAndReleaseBuffers(connection);
                return;
            }

            ClientConnection clientConnection;
            try {
                clientConnection = connector.connect(/*switch the two buffers to make a PROXY*/connection.outBuffer, connection.inBuffer);
            } catch (IOException e) {
                Logger.fatal(LogType.CONN_ERROR, "make passive connection failed, maybe provided endpoint info is invalid: " + e);
                // it should not happen if user provided endpoint is valid
                // but if it happens, we close both sides

                utilCloseConnectionAndReleaseBuffers(connection);
                return;
            }

            Session session = new Session(connection, clientConnection);
            ClientConnectionHandler handler = new SessionClientConnectionHandler(session);

            // we get a new event loop for handling
            // the event loop is provided by user
            // user may use the same loop as the acceptLoop
            //
            // and we only register the passive connection here
            // the active connection will be registered
            // when the passive connection is successfully established
            NetEventLoop loop = config.handleLoopProvider.get();
            if (loop == null) {
                // the loop not exist
                utilCloseSessionAndReleaseBuffers(session);
                Logger.warn(LogType.NO_EVENT_LOOP, "cannot get event loop for client connection " + clientConnection);
                return;
            }
            try {
                loop.addClientConnection(clientConnection, null, handler);

                // here the handler added successfully, we can record the session
                sessions.add(session);
                // the session record will be removed in `removed()` callback

            } catch (IOException e) {
                Logger.fatal(LogType.EVENT_LOOP_ADD_FAIL, "register passive connection into event loop failed, passive conn = " + clientConnection + ", err = " + e);
                // should not happen
                // but if it happens, we close both sides
                utilCloseSessionAndReleaseBuffers(session);
            }
        }

        class HandlerCallback extends Callback<Connector, IOException> {
            private final NetEventLoop loop;
            private final Connection active;

            HandlerCallback(NetEventLoop loop, Connection active) {
                this.loop = loop;
                this.active = active;
            }

            @Override
            protected void onSucceeded(Connector connector) {
                // remove the connection from loop first
                // because we want to remove the old ConnectionHandler
                // then handle it as direct
                try {
                    loop.removeConnection(active);
                } catch (Throwable t) {
                    // will raise error if it's not in the loop
                    // which should not happen
                    // but if happens, we close the connection
                    Logger.shouldNotHappen("remove the active connection from loop failed", t);
                    return;
                }
                // we don't care whether the connector is null or not
                // will be checked in the following method

                // handle like a normal proxy:
                handleDirect(active, connector);
            }

            @Override
            protected void onFailed(IOException err) {
                // we cannot handle the connection anymore
                // close it
                active.close();
                // we do not log here, the log should be in user code
            }
        }

        @SuppressWarnings(/*ignore generics here*/"unchecked")
        private void handleHandler(Connection connection) {
            // retrieve the handler
            ProtocolHandler pHandler = config.connGen.get().handler();
            // retrieve an event loop provided by user code
            // the net flow will be handled here
            NetEventLoop loop = config.handleLoopProvider.get();

            // create a protocol context and init the handler
            ProtocolHandlerContext pctx = new ProtocolHandlerContext(connection.id(), connection, loop.getSelectorEventLoop(), pHandler);
            pHandler.init(pctx);

            // set callback
            Tuple<Object, Callback<Connector, IOException>> tup = (Tuple) pctx.data;
            if (tup == null) {
                // user code fail to provide the data
                Logger.error(LogType.IMPROPER_USE, "user code should set a tuple(T, null) to the data field");
                // close the connection because we cannot handle it anymore
                connection.close();
                return;
            }
            tup = new Tuple<>(tup.left, new HandlerCallback(loop, connection));
            pctx.data = tup;

            // the following code should be same as in ProtocolServerHandler
            //noinspection Duplicates
            try {
                loop.addConnection(connection, pHandler, new ProtocolConnectionHandler(pctx));
            } catch (IOException e) {
                // handle exception in handler
                pHandler.exception(pctx, e);
                // and do some log
                Logger.error(LogType.EVENT_LOOP_ADD_FAIL, "add new connection into loop failed", e);
                // the connection should be closed by the lib
                connection.close();
            }
        }

        @Override
        public Tuple<RingBuffer, RingBuffer> getIOBuffers(NetworkChannel channel) {
            RingBuffer inBuffer = RingBuffer.allocateDirect(config.inBufferSize);
            RingBuffer outBuffer = RingBuffer.allocateDirect(config.outBufferSize);
            return new Tuple<>(inBuffer, outBuffer);
        }

        @Override
        public void removed(ServerHandlerContext ctx) {
            handler.serverRemoved(ctx.server);
        }
    }

    class SessionConnectionHandler implements ConnectionHandler {
        private final Session session;

        SessionConnectionHandler(Session session) {
            this.session = session;
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            // the input buffer is attached to remote write buffer
            // and output buffer is attached to remote read buffer
            // as a result,
            // the write and read process is automatically handled by the lib
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // we might write the last bytes here
            // when we write everything, we close the connection
            if (session.passive.isClosed() && ctx.connection.outBuffer.used() == 0)
                utilCloseConnectionAndReleaseBuffers(ctx.connection);
        }

        @Override
        public void exception(ConnectionHandlerContext ctx, IOException err) {
            Logger.error(LogType.CONN_ERROR, "session got exception: " + err);
            // close both sides
            utilCloseSessionAndReleaseBuffers(session);
        }

        @Override
        public void closed(ConnectionHandlerContext ctx) {
            assert Logger.lowLevelDebug("now the connection is closed, we should close the session");
            // now the active connection is closed
            if (session.isClosed()) // do nothing if the session is already closed
                return;
            if (session.passive.outBuffer.used() == 0) {
                // nothing to write anymore
                // close the passive connection
                assert Logger.lowLevelDebug("nothing to write for passive connection, do close");
                utilCloseConnectionAndReleaseBuffers(session.passive);
            } else {
                assert Logger.lowLevelDebug("we should close the passive connection after everything wrote");
                // and we close the active conn's output buffer, i.e. passive's input buffer
                // then the passive will not be able to write anything to active

                // the passive can still read from the active conn's in-buffer if still got some bytes
                session.passive.inBuffer.close();
            }
        }

        @Override
        public void removed(ConnectionHandlerContext ctx) {
            utilCloseSessionAndReleaseBuffers(session);
        }
    }

    class SessionClientConnectionHandler implements ClientConnectionHandler {
        private final Session session;
        private boolean isConnected = false;

        SessionClientConnectionHandler(Session session) {
            this.session = session;
        }

        @Override
        public void connected(ClientConnectionHandlerContext ctx) {
            assert Logger.lowLevelDebug("passive connection established: " + ctx.connection);
            isConnected = true; // it's connected

            // now we can add active connection into event loop
            // use event loop from context
            // the active and passive connection are handled in the same loop
            try {
                ctx.eventLoop.addConnection(session.active, null, new SessionConnectionHandler(session));
            } catch (IOException e) {
                Logger.fatal(LogType.EVENT_LOOP_ADD_FAIL, "register active connection into event loop failed, conn = " + session.active + ", err = " + e);
                // add into event loop failed
                // close session
                assert Logger.lowLevelDebug("nothing to write for active connection, do close");
                utilCloseSessionAndReleaseBuffers(session);
            }
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            // see readable in SessionConnectHandler#readable
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // we might write the last bytes here
            // when we write everyhing, we close the connection
            if (session.active.isClosed() && ctx.connection.outBuffer.used() == 0)
                utilCloseConnectionAndReleaseBuffers(ctx.connection);
        }

        @Override
        public void exception(ConnectionHandlerContext ctx, IOException err) {
            Logger.error(LogType.CONN_ERROR, "session got exception: " + err);
            // close both sides
            utilCloseSessionAndReleaseBuffers(session);

            if (!isConnected) {
                // the connection failed before established
                // we should alert the connector that the connection failed
                Connector connector = ((ClientConnection) ctx.connection).getConnector();
                if (connector != null) {
                    connector.connectionFailed();
                }
            }
        }

        @Override
        public void closed(ConnectionHandlerContext ctx) {
            assert Logger.lowLevelDebug("now the passive connection is closed, we should close the session");
            // now the passive connection is closed
            if (session.isClosed()) // do nothing if the session is already closed
                return;
            if (session.active.outBuffer.used() == 0) {
                // nothing to write anymore
                // close the active connection
                utilCloseConnectionAndReleaseBuffers(session.active);
            } else {
                assert Logger.lowLevelDebug("we should close the active connection after everything wrote");
                // and we close the passive conn's output buffer, i.e. active's input buffer
                // then the active will not be able to write anything to passive

                // the active can still read from the passive conn's in-buffer if still got some bytes
                session.active.inBuffer.close();
            }
        }

        @Override
        public void removed(ConnectionHandlerContext ctx) {
            utilCloseSessionAndReleaseBuffers(session);
            sessions.remove(session); // remove the session record
        }
    }

    private final ProxyNetConfig config;
    private final ProxyEventHandler handler;
    private final ConcurrentHashSet<Session> sessions = new ConcurrentHashSet<>();

    public Proxy(ProxyNetConfig config, ProxyEventHandler handler) {
        this.handler = handler;
        this.config = config;
    }

    public void handle() throws IOException {
        utilValidate(config);
        config.acceptLoop.addServer(config.server, null, new SessionServerHandler());
    }

    public void stop() {
        config.acceptLoop.removeServer(config.server);
    }

    public int sessionCount() {
        return sessions.size();
    }

    public void copySessions(Collection<? super Session> coll) {
        coll.addAll(sessions);
    }
}
