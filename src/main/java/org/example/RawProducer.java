package org.example;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

public class RawProducer extends Thread{
    private final String endpoint;
    private final String productId;
    private final String tag;
    private final static String QUEUE_NAME = "CoinBaseQueue";

    private final BigAtomicCounter counter;

    private static final ConnectionFactory factory = new ConnectionFactory();

    static {
        factory.setHost("localhost");
        factory.setPort(5672);
        // Add more configuration as needed
    }
    public RawProducer(String endpoint, String productId, String tag, BigAtomicCounter counter) {
        this.endpoint = endpoint;
        this.productId = productId;
        this.tag = tag;
        this.counter = counter;
    }

    private static Connection createConnection() throws IOException, TimeoutException {
        factory.setHost("localhost");
        return factory.newConnection();
    }
    public void run() {
        HttpClient client = HttpClient.newHttpClient();
        WebSocket.Builder wsBuilder = client.newWebSocketBuilder();
        WebSocket webSocket = wsBuilder.buildAsync(URI.create(endpoint), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                handleMessage(data.toString());
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
        }).join();

        // Subscribe to the product feed
        String subscribeMessage = String.format(
                "{\"type\":\"subscribe\",\"product_ids\":[\"%s\"],\"channels\":[\"matches\"]}",
                productId
        );
        webSocket.sendText(subscribeMessage, true);

        // Keep the connection alive
        while (true) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
    }

    private void handleMessage(String message) {
        JSONObject json = new JSONObject(message);
        if (json.getString("type").equals("match")) {
            String data = String.format("%s,%s,%s,%s",
                    json.getString("time"),
                    json.getString("product_id"),
                    json.getString("price"),
                    json.getString("size")
            );
            publishToConsumers(tag + ": " + data);
        }
    }

    private void publishToConsumers(String data) {
        // Using Rabbit to produce information
//        System.out.println("subut info");
        try (Connection connection = createConnection();
             Channel channel = connection.createChannel()){
//            System.out.println("connection establish");
             channel.queueDeclare(QUEUE_NAME, false, false, false, null);
//             data = data + " with thread:" + Thread.currentThread() + ", in counter" + this.counter.incrementAndGet().toString();
             System.out.println(data);
             channel.basicPublish("", QUEUE_NAME, null, data.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String endpoint = "wss://ws-feed.pro.coinbase.com";
        String productId = "BTC-USD";
        String tag = "trade@coinbase";
        BigAtomicCounter counter = new BigAtomicCounter();

//        RawProducer producer = new RawProducer(endpoint, productId, tag);
        Thread thread1 = new RawProducer(endpoint, productId, tag, counter);
//        Thread thread2 = new RawProducer(endpoint, productId, tag, counter);
        thread1.start();
//        thread2.start();
        try {
            thread1.join();
//            thread2.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}