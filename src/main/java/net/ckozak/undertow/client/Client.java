package net.ckozak.undertow.client;

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.UndertowClient;
import io.undertow.connector.ByteBufferPool;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.Protocols;
import io.undertow.util.StringReadChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.ssl.XnioSsl;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;

public class Client {

    public static void main(String[] args) throws Exception {
        Xnio xnio = Xnio.getInstance(UndertowClient.class.getClassLoader());
        XnioWorker worker = xnio.createWorker(OptionMap.builder()
                .set(Options.TCP_NODELAY, true)
                .set(Options.KEEP_ALIVE, true)
                .set(Options.WORKER_IO_THREADS, 4)
                .getMap());
        ByteBufferPool buffers = new DefaultByteBufferPool(true, 16 * 1024, -1, 4);
        XnioSsl ssl = new UndertowXnioSsl(xnio, OptionMap.EMPTY, buffers, SSLContext.getDefault());
        UndertowClient undertowClient = UndertowClient.getInstance();

        CompletableFuture<String> future = new CompletableFuture<>();
        ClientConnection connection;
        try {
            final URI uri = new URI("https://pastebin.com/raw/t7c0X5JW");

            // Now read using default settings from the jvm to ensure the default ssl context is configured properly
            byte[] buffer = new byte[1024];
            int read = uri.toURL().openStream().read(buffer);
            System.out.println("read: " + read);
            String response = new String(buffer, 0, read);
            System.out.println("response: " + response);

            connection = undertowClient.connect(uri, worker, ssl, buffers, OptionMap.EMPTY).get();
            connection.getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    final ClientRequest request = new ClientRequest()
                            .setProtocol(Protocols.HTTP_1_1)
                            .setMethod(Methods.GET)
                            .setPath(uri.getPath());
                    request.getRequestHeaders().put(Headers.HOST, uri.getHost());
                    request.getRequestHeaders().put(Headers.ACCEPT, "*/*");
                    connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange result) {
                            result.setResponseListener(new ClientCallback<ClientExchange>() {
                                @Override
                                public void completed(ClientExchange result) {
                                    if (result.getResponse().getResponseCode() != 200) {
                                        future.completeExceptionally(new RuntimeException("Received status code " +
                                                result.getResponse().getResponseCode()));
                                        return;
                                    }
                                    HeaderMap requestHeaders = result.getRequest().getRequestHeaders();
                                    for (HttpString header : requestHeaders.getHeaderNames()) {
                                        System.out.printf("\t%s: %s\n", header, requestHeaders.getFirst(header));
                                    }

                                    System.out.println("====");

                                    HeaderMap headerMap = result.getResponse().getResponseHeaders();
                                    for (HttpString header : headerMap.getHeaderNames()) {
                                        System.out.printf("\t%s: %s\n", header, headerMap.getFirst(header));
                                    }

                                    new StringReadChannelListener(result.getConnection().getBufferPool()) {

                                        @Override
                                        protected void stringDone(String string) {
                                            System.out.println("completed with: " + string);
                                            future.complete(string);
                                        }

                                        @Override
                                        protected void error(IOException e) {
                                            future.completeExceptionally(e);
                                        }
                                    }.setup(result.getResponseChannel());
                                }

                                @Override
                                public void failed(IOException e) {
                                    future.completeExceptionally(e);
                                }
                            });

                            try {
                                result.getRequestChannel().shutdownWrites();
                                if(!result.getRequestChannel().flush()) {
                                    result.getRequestChannel().getWriteSetter().set(ChannelListeners.<StreamSinkChannel>flushingChannelListener(null, null));
                                    result.getRequestChannel().resumeWrites();
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                                future.completeExceptionally(e);
                            }
                        }

                        @Override
                        public void failed(IOException e) {
                            future.completeExceptionally(e);
                        }
                    });
                }

            });
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Failed to create request", e);
        }
        try {
            System.out.println("RESULT IS: '" + future.get() + "'");
        } finally {
            connection.close();
            worker.shutdownNow();
        }
    }
}
