/**
 * Copyright  Vitalii Rudenskyi (vrudenskyi@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mckesson.kafka.connect.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.collections4.MapUtils;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mckesson.kafka.connect.source.APIClientException;
import com.mckesson.kafka.connect.source.PollableAPIClient;
import com.mckesson.kafka.connect.utils.OkHttpClientConfig;
import com.mckesson.kafka.connect.utils.UrlBuilder;

import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Generic implementation for simple http client based pollers. 
 * 
 * @author Vitalii Rudenskyi
 *
 */
public abstract class HttpAPIClient implements PollableAPIClient {

  private static final Logger log = LoggerFactory.getLogger(HttpAPIClient.class);

  public static final String SERVER_URI_CONFIG = "http.serverUri";
  public static final String ENDPPOINT_CONFIG = "http.endpoint";

  public static final String AUTH_TYPE_CONFIG = "http.auth.type";
  public static final String AUTH_TYPE_DEFAULT = "none";
  public static final String AUTH_CLASS_CONFIG = "http.auth.class";

  protected enum AuthType {
    NONE, BASIC, NTLM, CUSTOM;

    public Authenticator getAuthenticator() {
      Authenticator auth;
      switch (this) {
        case BASIC:
          auth = new BasicAuthenticator();
          break;

        case NTLM:
          auth = new NTLMAuthenticator();
          break;

        case NONE:
          auth = new NoneAuthenticator();
          break;

        case CUSTOM:
        default:
          auth = null;
          break;
      }

      return auth;

    }

  };

  public static final ConfigDef CONFIG_DEF = new ConfigDef()
      .define(SERVER_URI_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE, ConfigDef.Importance.HIGH, "URI of Jira server. Required.")
      .define(ENDPPOINT_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE, ConfigDef.Importance.HIGH, "Endpoint")
      .define(AUTH_TYPE_CONFIG, ConfigDef.Type.STRING, AUTH_TYPE_DEFAULT, ConfigDef.Importance.MEDIUM, "Authtype. Default: none")
      .define(AUTH_CLASS_CONFIG, ConfigDef.Type.CLASS, null, ConfigDef.Importance.LOW, "Custom auth class");

  protected static final String PARTITION_URL_KEY = "___URL";
  protected static final String PARTITION_METHOD_KEY = "___METHOD";

  protected final ObjectMapper mapper = new ObjectMapper();
  protected OkHttpClient httpClient;

  protected String serverUri;
  protected String endpoint;
  protected String httpMethod = "GET";

  @Override
  public void configure(Map<String, ?> configs) {

    log.debug("Configuring HttpAPIClient...");
    SimpleConfig conf = new SimpleConfig(CONFIG_DEF, configs);
    this.serverUri = conf.getString(SERVER_URI_CONFIG);
    this.endpoint = conf.getString(ENDPPOINT_CONFIG);

    Authenticator authenticator;
    AuthType authType = AuthType.valueOf(conf.getString(AUTH_TYPE_CONFIG).toUpperCase());
    if (AuthType.CUSTOM.equals(authType)) {
      authenticator = conf.getConfiguredInstance(AUTH_CLASS_CONFIG, Authenticator.class);
    } else {
      authenticator = authType.getAuthenticator();
      ((Configurable) authenticator).configure(configs);
    }

    try {
      this.httpClient = OkHttpClientConfig.buildClient(configs, authenticator);
    } catch (Exception e) {
      throw new ConnectException("Failed to configure http client", e);
    }
    log.debug("HttpAPIClient configured.");
  }

  @Override
  public List<SourceRecord> poll(String topic, Map<String, Object> partition, Map<String, Object> offset, int itemsToPoll, AtomicBoolean stop) throws APIClientException {

    List<SourceRecord> pollResult;
    Request.Builder rBuilder = getRequestBuilder(partition, offset, itemsToPoll);
    if (rBuilder == null) {
      log.debug("No request built, exit poll");
      return Collections.emptyList();
    }

    Request request = rBuilder.build();
    try (Response response = httpClient.newCall(request).execute()) { //TODO: implement async call
      List<Object> data = processResponse(partition, offset, response);
      pollResult = createRecords(topic, partition, offset, data);
      updateOffset(topic, partition, offset, response, pollResult);
    } catch (IOException e) {
      throw new APIClientException(e);
    }
    return pollResult;
  }

  /**
   * When data extracted and records created update offset.
   * 
   * @param topic
   * @param partition
   * @param offset
   * @param response 
   * @param pollResult
   */
  protected void updateOffset(String topic, Map<String, Object> partition, Map<String, Object> offset, Response response, List<SourceRecord> pollResult) {

  }

  /**
   * Checks response from server and calls {@code extractData} method
   * 
   * @param partition
   * @param offset
   * @param response
   * @return
   * @throws APIClientException
   * @throws IOException
   */
  protected List<Object> processResponse(Map<String, Object> partition, Map<String, Object> offset, Response response) throws APIClientException, IOException {

    if (!response.isSuccessful()) {
      log.error("Unexpected code: {}  \n\t with body: {} \n\t for partition: {} \n\t offset: {}\n", response, response.body().string(), partition, offset);
      throw new APIClientException("Unexpected code: " + response);
    }
    log.debug("HttpRequest:{} sucessfully finished. with code: {}", response.request().url(), response.code());
    return extractData(partition, offset, response);
  }

  public abstract List<Object> extractData(Map<String, Object> partition, Map<String, Object> offset, Response response) throws APIClientException;

  /**
   * Creates builder for the request
   * 
   * @param partition
   * @param offset
   * @param itemsToPoll
   * @return - can return null to skip the poll 
   */
  protected Request.Builder getRequestBuilder(Map<String, Object> partition, Map<String, Object> offset, int itemsToPoll) {
    Request.Builder requestBuilder = new Request.Builder()
        .url(partition.get(PARTITION_URL_KEY).toString())
        .method(partition.get(PARTITION_METHOD_KEY).toString(), null);
    return requestBuilder;
  }

  /**
   * Creates builder for the request
   * 
   * @param partition
   * @param offset
   * @param itemsToPoll
   * @param urlParams
   * @param queryParams
   * @return - can return null to skip the poll 
   */
  protected Request.Builder getRequestBuilder(Map<String, Object> partition, Map<String, Object> offset, int itemsToPoll, Map<String, String> urlParams, Map<String, String> queryParams) {

    UrlBuilder ub = new UrlBuilder(partition.get(PARTITION_URL_KEY).toString());

    if (MapUtils.isNotEmpty(urlParams)) {
      urlParams.forEach((name, value) -> {
        ub.routeParam(name, value);
      });
    }
    if (MapUtils.isNotEmpty(queryParams)) {
      queryParams.forEach((name, value) -> {
        ub.queryString(name, value);
      });
    }

    Request.Builder requestBuilder = new Request.Builder()
        .url(partition.get(PARTITION_URL_KEY).toString())
        .method(partition.get(PARTITION_METHOD_KEY).toString(), null);
    return requestBuilder;
  }

  protected List<SourceRecord> createRecords(String topic, Map<String, Object> partition, Map<String, Object> offset, List<Object> dataList) {

    List<SourceRecord> result = new ArrayList<>(dataList.size());
    for (Object value : dataList) {
      SourceRecord r = new SourceRecord(partition, offset, topic, null, null, null, value);
      r.headers().addString("http.source", partition.get(PARTITION_URL_KEY).toString());
      result.add(r);
    }

    return result;
  }

  @Override
  public List<Map<String, Object>> partitions() throws APIClientException {
    List<Map<String, Object>> partitions = new ArrayList<>(1);
    Map<String, String> p = new HashMap<>(2);
    p.put(PARTITION_URL_KEY, new UrlBuilder(this.serverUri + this.endpoint).getUrl());
    p.put(PARTITION_METHOD_KEY, this.httpMethod);
    partitions.add(Collections.unmodifiableMap(p));
    return partitions;
  }

  @Override
  public Map<String, Object> initialOffset(Map<String, Object> partition) throws APIClientException {
    Map<String, Object> offset = new HashMap<>(0);
    return offset;
  }

  @Override
  public void close() {
  }

}
