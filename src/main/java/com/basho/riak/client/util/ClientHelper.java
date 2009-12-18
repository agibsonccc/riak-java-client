/*
 * This file is provided to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.basho.riak.client.util;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PutMethod;
import org.json.JSONException;
import org.json.JSONObject;

import com.basho.riak.client.RiakClient;
import com.basho.riak.client.RiakConfig;
import com.basho.riak.client.RiakObject;
import com.basho.riak.client.request.RequestMeta;
import com.basho.riak.client.request.RiakWalkSpec;
import com.basho.riak.client.response.DefaultHttpResponse;
import com.basho.riak.client.response.HttpResponse;
import com.basho.riak.client.response.RiakIOException;
import com.basho.riak.client.response.StreamHandler;

/**
 * This class performs the actual HTTP requests underlying the operations in the
 * RiakClient interface and returns the resulting HTTP responses. It is up to
 * actual implementations of RiakClient to interpret the responses and translate
 * them into the appropriate format.
 */
public class ClientHelper {

    private RiakConfig config;
    private HttpClient httpClient;

    public ClientHelper(RiakConfig config) {
        this.config = config;
        httpClient = ClientUtils.newHttpClient(config);
    }

    /**
     * See {@link RiakClient}
     * 
     * @param bucket
     *            Same as RiakClient.setBucketSchema()
     * @param schemaKey
     *            The JSON key which holds the schema or null to send the schema
     *            without an enclosing key
     * @param schema
     *            Same as RiakClient.setBucketSchema()
     * @param meta
     *            Same as RiakClient.setBucketSchema()
     */
    public HttpResponse setBucketSchema(String bucket, String schemaKey, Map<String, Object> schema, RequestMeta meta) {
        if (meta == null) {
            meta = new RequestMeta();
        }

        meta.put(Constants.HDR_ACCEPT, Constants.CTYPE_JSON);

        JSONObject json;
        try {
            JSONObject props = new JSONObject(schema);
            if (schemaKey != null) {
                json = new JSONObject().put(schemaKey, props);
            } else {
                json = props;
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("Cannot serialize provided schema into JSON", e);
        }

        PutMethod put = new PutMethod(ClientUtils.makeURI(config, bucket));
        put.setRequestEntity(new ByteArrayRequestEntity(json.toString().getBytes(), Constants.CTYPE_JSON));

        return executeMethod(bucket, null, put, meta);
    }

    public HttpResponse setBucketSchema(String bucket, String schemaKey, Map<String, Object> schema) {
        return setBucketSchema(bucket, schemaKey, schema, null);
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public HttpResponse listBucket(String bucket, RequestMeta meta) {
        GetMethod get = new GetMethod(ClientUtils.makeURI(config, bucket));
        get.setRequestHeader(Constants.HDR_CONTENT_TYPE, Constants.CTYPE_JSON);
        get.setRequestHeader(Constants.HDR_ACCEPT, Constants.CTYPE_JSON);
        return executeMethod(bucket, null, get, meta);
    }

    public HttpResponse listBucket(String bucket) {
        return listBucket(bucket, null);
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public HttpResponse store(RiakObject object, RequestMeta meta) {
        if (meta == null) {
            meta = new RequestMeta();
        }
        String bucket = object.getBucket();
        String key = object.getKey();
        String url = ClientUtils.makeURI(config, bucket, key, "?" + meta.getQueryParams());
        PutMethod put = new PutMethod(url);

        if (object.getEntityStream() != null) {
            if (object.getEntityStreamLength() >= 0) {
                put.setRequestEntity(new InputStreamRequestEntity(object.getEntityStream(),
                                                                  object.getEntityStreamLength(),
                                                                  object.getContentType()));
            } else {
                put.setRequestEntity(new InputStreamRequestEntity(object.getEntityStream(), object.getContentType()));
            }
        } else if (object.getEntity() != null) {
            put.setRequestEntity(new ByteArrayRequestEntity(object.getEntity().getBytes(), object.getContentType()));
        }

        return executeMethod(bucket, key, put, meta);
    }

    public HttpResponse store(RiakObject object) {
        return store(object, null);
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public HttpResponse fetchMeta(String bucket, String key, RequestMeta meta) {
        if (meta == null) {
            meta = new RequestMeta();
        }
        if (meta.getQueryParam(Constants.QP_R) == null) {
            meta.addQueryParam(Constants.QP_R, Constants.DEFAULT_R.toString());
        }
        HeadMethod head = new HeadMethod(ClientUtils.makeURI(config, bucket, key, "?" + meta.getQueryParams()));
        return executeMethod(bucket, key, head, meta);
    }

    public HttpResponse fetchMeta(String bucket, String key) {
        return fetchMeta(bucket, key, null);
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public HttpResponse fetch(String bucket, String key, RequestMeta meta) {
        if (meta == null) {
            meta = new RequestMeta();
        }
        if (meta.getQueryParam(Constants.QP_R) == null) {
            meta.addQueryParam(Constants.QP_R, Constants.DEFAULT_R.toString());
        }
        GetMethod get = new GetMethod(ClientUtils.makeURI(config, bucket, key, "?" + meta.getQueryParams()));
        return executeMethod(bucket, key, get, meta);
    }

    public HttpResponse fetch(String bucket, String key) {
        return fetch(bucket, key, null);
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public boolean stream(String bucket, String key, StreamHandler handler, RequestMeta meta) throws IOException {
        if (meta == null) {
            meta = new RequestMeta();
        }
        if (meta.getQueryParam(Constants.QP_R) == null) {
            meta.addQueryParam(Constants.QP_R, Constants.DEFAULT_R.toString());
        }
        GetMethod get = new GetMethod(ClientUtils.makeURI(config, bucket, key, "?" + meta.getQueryParams()));
        try {
            int status = httpClient.executeMethod(get);
            if (handler == null)
                return true;

            return handler.process(bucket, key, status, ClientUtils.asHeaderMap(get.getResponseHeaders()),
                                   get.getResponseBodyAsStream(), get);
        } finally {
            get.releaseConnection();
        }
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public HttpResponse delete(String bucket, String key, RequestMeta meta) {
        if (meta == null) {
            meta = new RequestMeta();
        }
        String url = ClientUtils.makeURI(config, bucket, key, "?" + meta.getQueryParams());
        DeleteMethod delete = new DeleteMethod(url);
        return executeMethod(bucket, key, delete, meta);
    }

    public HttpResponse delete(String bucket, String key) {
        return delete(bucket, key, null);
    }

    /**
     * Same as {@link RiakClient}, except only returning the HTTP response
     */
    public HttpResponse walk(String bucket, String key, String walkSpec, RequestMeta meta) {
        GetMethod get = new GetMethod(ClientUtils.makeURI(config, bucket, key, walkSpec));
        return executeMethod(bucket, key, get, meta);
    }

    public HttpResponse walk(String bucket, String key, String walkSpec) {
        return walk(bucket, key, walkSpec, null);
    }

    public HttpResponse walk(String bucket, String key, RiakWalkSpec walkSpec) {
        return walk(bucket, key, walkSpec.toString(), null);
    }

    /**
     * @return The config used to construct the HttpClient connecting to Riak.
     */
    protected RiakConfig getConfig() {
        return config;
    }

    /**
     * Perform and HTTP request and return the resulting response using the
     * internal HttpClient.
     * 
     * @param bucket
     *            Bucket of the object receiving the request.
     * @param key
     *            Key of the object receiving the request or null if the request
     *            is for a bucket.
     * @param httpMethod
     *            The HTTP request to perform; must not be null.
     * @param meta
     *            Extra HTTP headers to attach to the request. Query parameters
     *            are ignored; they should have already been used to construct
     *            <code>httpMethod</code> and query parameters.
     * @return The HTTP response returned by Riak from executing
     *         <code>httpMethod</code>
     * @throws RiakIOException
     *             If an error occurs during communication with the Riak server
     *             (i.e. HttpClient threw an IOException)
     */
    protected HttpResponse executeMethod(String bucket, String key, HttpMethod httpMethod, RequestMeta meta) {

        if (meta != null) {
            Map<String, String> headers = meta.getHttpHeaders();
            for (String header : headers.keySet()) {
                httpMethod.setRequestHeader(header, headers.get(header));
            }
        }

        try {
            httpClient.executeMethod(httpMethod);
            return DefaultHttpResponse.fromHttpMethod(bucket, key, httpMethod);
        } catch (IOException e) {
            throw new RiakIOException(e);
        } finally {
            httpMethod.releaseConnection();
        }
    }
}