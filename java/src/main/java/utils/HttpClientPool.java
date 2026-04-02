package utils;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import java.util.Map;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * @author by laugh on 2016/3/29.
 */
public class HttpClientPool {

    private static volatile HttpClientPool clientInstance;
    private HttpClient httpClient;

    public static HttpClientPool getHttpClient() {
        HttpClientPool tmp = clientInstance;
        if (tmp == null) {
            synchronized (HttpClientPool.class) {
                tmp = clientInstance;
                if (tmp == null) {
                    tmp = new HttpClientPool();
                    clientInstance = tmp;
                }
            }
        }
        return tmp;
    }

    private HttpClientPool() {
        buildHttpClient();
    }

    private void buildHttpClient() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(100, TimeUnit.SECONDS);
        connectionManager.setMaxTotal(200);// 连接池
        connectionManager.setDefaultMaxPerRoute(100);// 每条通道的并发连接数
        // 连接超时 5 秒，读超时 15 秒（新浪接口有时较慢，避免 Read timed out）
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(5000)
                .setConnectTimeout(5000)
                .setSocketTimeout(15000)
                .build();
        HttpClientBuilder httpClientBuilder = HttpClients.custom().setConnectionManager(connectionManager);
        httpClient =httpClientBuilder.setDefaultRequestConfig(requestConfig).build();
    }

    /**
     * GET 请求，可设置请求头（如 Referer，用于新浪 K 线等接口）
     */
    public String get(String url, Map<String, String> headers) throws Exception {
        HttpGet httpGet = new HttpGet(url);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                httpGet.setHeader(e.getKey(), e.getValue());
            }
        }
        return getResponseContent(url, httpGet);
    }

    private String getResponseContent(String url, HttpRequestBase request) throws Exception {
        HttpResponse response = null;
        try {
            response = httpClient.execute(request);
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new Exception("got an error from HTTP for url : " + URLDecoder.decode(url, "UTF-8"),e);
        } finally {
            if(response != null){
                EntityUtils.consumeQuietly(response.getEntity());
            }
            request.releaseConnection();
        }
    }
}