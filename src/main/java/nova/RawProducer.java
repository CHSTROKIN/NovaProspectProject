package nova;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

public class RawProducer extends Thread implements Producer {
  private final String endpoint;
  private final String productId;
  private final String tag;
  private static final String QUEUE_NAME = NovaConstant.QUEUE_NAME;
  private static final String EXCHANGE_NAME = NovaConstant.EXCHANGE_NAME;

  private final BigAtomicCounter counter;

  private static final ConnectionFactory factory = new ConnectionFactory();

  static {
    factory.setHost(NovaConstant.HOST);
    factory.setPort(NovaConstant.PORT);
    factory.setRequestedHeartbeat(NovaConstant.HEADER_BEAT);
    factory.setAutomaticRecoveryEnabled(true);
    factory.setNetworkRecoveryInterval(NovaConstant.RECOVER);
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
    WebSocket webSocket =
        wsBuilder
            .buildAsync(
                URI.create(endpoint),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    handleMessage(data.toString());
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    // Subscribe to the product feed
    String subscribeMessage =
        String.format(
            "{\"type\":\"subscribe\",\"product_ids\":[\"%s\"],\"channels\":[\"matches\"]}",
            productId);
    webSocket.sendText(subscribeMessage, true);

    // Keep the connection alive
    while (true) {
      try {
        Thread.sleep(NovaConstant.PRODUCER_WAIT_TIME);
      } catch (InterruptedException e) {
        e.printStackTrace();
        break;
      }
    }
  }

  public void handleMessage(String message) {
    JSONObject json = new JSONObject(message);
    if (json.getString("type").equals("match")) {
      String data =
          String.format(
              "%s,%s,%s,%s",
              json.getString("time"),
              json.getString("product_id"),
              json.getString("price"),
              json.getString("size"));
      publishToConsumers(tag + "," + data);
    }
  }

  @Override
  public void publishToConsumers(String data) {
    try (Connection connection = createConnection();
        Channel channel = connection.createChannel()) {

      // Declare a durable exchange
      channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.DIRECT, true);

      // Declare a durable queue
      channel.queueDeclare(QUEUE_NAME, true, false, false, null);

      // Bind the queue to the exchange
      channel.queueBind(QUEUE_NAME, EXCHANGE_NAME, "");

      AMQP.BasicProperties properties =
          new AMQP.BasicProperties.Builder()
              .deliveryMode(2) // Make message persistent
              .headers(Collections.singletonMap("sequence", this.counter.get().longValue()))
              .build();

      data = data + "," + this.counter.getAndIncrement().toString();

      channel.basicPublish(EXCHANGE_NAME, "", properties, data.getBytes(StandardCharsets.UTF_8));
      System.out.println("Produced: " + data);

    } catch (IOException | TimeoutException e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    // Testing script for development
    String endpoint = "wss://ws-feed.pro.coinbase.com";
    String productId = "BTC-USD";
    String tag = "trade@coinbase";
    BigAtomicCounter counter = new BigAtomicCounter();
    Thread thread1 = new RawProducer(endpoint, productId, tag, counter);
    thread1.start();
    try {
      thread1.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
