package xyz.ressor.source.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.core.Options;
import org.apache.commons.io.IOUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.ressor.source.SourceVersion;
import xyz.ressor.source.http.version.ETag;
import xyz.ressor.source.version.LastModified;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static xyz.ressor.source.http.CacheControlStrategy.*;

public class HttpSourceTest {
    public static final String PATH = "/resource";
    private WireMockServer wireMockServer;

    @BeforeEach
    public void setUp() {
        wireMockServer = new WireMockServer(Options.DYNAMIC_PORT);
        wireMockServer.start();
        configureFor(wireMockServer.port());
    }

    @AfterEach
    public void tearDown() {
        wireMockServer.stop();
    }

    @Test
    public void testNoCacheSupportedScenario() throws Exception {
        stubFor(getPath()
                .willReturn(aResponse()
                        .withStatus(200).withBody("one")));

        var source = Http.source(defaultURL()).cacheControlStrategy(NONE).build();
        assertThat(IOUtils.toString(source.load().getInputStream(), UTF_8)).isEqualTo("one");
        assertThat(IOUtils.toString(source.loadIfModified(new LastModified(System.currentTimeMillis()))
                .getInputStream(), UTF_8)).isEqualTo("one");
    }

    @Test
    public void testIfModifiedSinceScenario() throws Exception {
        stubFor(getPath().willReturn(aResponse().withStatus(200).withBody("init")
                .withHeader("Last-Modified", "Fri, 4 Oct 2019 18:58:30 GMT")));
        stubFor(getPath().withHeader("If-Modified-Since", equalTo("Fri, 4 Oct 2019 18:58:30 GMT"))
                .willReturn(aResponse().withStatus(304)));
        stubFor(getPath().withHeader("If-Modified-Since", equalTo("Fri, 4 Oct 2019 18:58:31 GMT"))
                .willReturn(aResponse().withStatus(200).withHeader("Last-Modified", "Fri, 4 Oct 2019 18:58:32 GMT")
                        .withBody("one")));

        var source = Http.source(defaultURL()).cacheControlStrategy(IF_MODIFIED_SINCE).pool(5, 10000).build();
        var loadedResource = source.load();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("init");

        assertThat(source.loadIfModified(loadedResource.getVersion())).isNull();
        verify(exactly(1), getRequestedFor(urlPathEqualTo(PATH)).withHeader("If-Modified-Since",
                equalTo("Fri, 4 Oct 2019 18:58:30 GMT")));

        loadedResource = source.loadIfModified(new LastModified(1570215511000L));
        assertThat(loadedResource).isNotNull();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("one");
        assertThat((long) loadedResource.getVersion().val()).isEqualTo(1570215512000L);
    }

    @Test
    public void testETagScenario() throws Exception {
        stubFor(getPath().willReturn(aResponse().withStatus(200).withBody("init").withHeader("ETag", "1ab")));
        stubFor(getPath().withHeader("If-None-Match", equalTo("1ab")).willReturn(aResponse().withStatus(304)));
        stubFor(getPath().withHeader("If-None-Match", equalTo("1ac")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "1ad").withBody("one")));

        var source = Http.source(defaultURL()).cacheControlStrategy(ETAG).socketTimeoutMs(10000).connectTimeoutMs(10000).build();
        var loadedResource = source.load();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("init");

        assertThat(source.loadIfModified(loadedResource.getVersion())).isNull();
        verify(exactly(1), getRequestedFor(urlPathEqualTo(PATH)).withHeader("If-None-Match", equalTo("1ab")));

        loadedResource = source.loadIfModified(new ETag("1ac"));
        assertThat(loadedResource).isNotNull();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("one");
        assertThat(loadedResource.getVersion().val().toString()).isEqualTo("1ad");
    }

    @Test
    public void testIfModifiedHeadScenario() throws Exception {
        var response = aResponse().withStatus(200).withHeader("Last-Modified", "Fri, 4 Oct 2019 18:58:30 GMT");
        stubFor(headPath().willReturn(response));
        stubFor(getPath().willReturn(response.withBody("init")));

        var source = new HttpSource(client(), defaultURL(), CacheControlStrategy.LAST_MODIFIED_HEAD);
        var loadedResource = source.load();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("init");
        verify(exactly(0), headRequestedFor(urlPathEqualTo(PATH)));
        verify(exactly(1), getRequestedFor(urlPathEqualTo(PATH)));

        assertThat(source.loadIfModified(loadedResource.getVersion())).isNull();
        verify(exactly(1), headRequestedFor(urlPathEqualTo(PATH)));
        verify(exactly(1), getRequestedFor(urlPathEqualTo(PATH)));

        removeAllMappings();

        response = aResponse().withStatus(200).withHeader("Last-Modified", "Fri, 4 Oct 2019 18:58:31 GMT");
        stubFor(headPath().willReturn(response));
        stubFor(getPath().willReturn(response.withBody("one")));

        loadedResource = source.loadIfModified(loadedResource.getVersion());
        assertThat(loadedResource).isNotNull();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("one");
    }

    @Test
    public void testETagHeadScenario() throws Exception {
        var response = aResponse().withStatus(200).withHeader("ETag", "1ab");
        stubFor(headPath().willReturn(response));
        stubFor(getPath().willReturn(response.withBody("init")));

        var source = Http.source(defaultURL()).cacheControlStrategy(ETAG_HEAD).receiveBufferSize(5).build();
        var loadedResource = source.load();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("init");
        verify(exactly(0), headRequestedFor(urlPathEqualTo(PATH)));
        verify(exactly(1), getRequestedFor(urlPathEqualTo(PATH)));

        assertThat(source.loadIfModified(loadedResource.getVersion())).isNull();
        verify(exactly(1), headRequestedFor(urlPathEqualTo(PATH)));
        verify(exactly(1), getRequestedFor(urlPathEqualTo(PATH)));

        removeAllMappings();

        response = aResponse().withStatus(200).withHeader("ETag", "1ac");
        stubFor(headPath().withHeader("If-None-Match", equalTo("1ab")).willReturn(response));
        stubFor(getPath().willReturn(response.withBody("one")));

        loadedResource = source.loadIfModified(loadedResource.getVersion());
        assertThat(loadedResource).isNotNull();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("one");
    }

    @Test
    public void testMixedImplementationsScenario() throws Exception {
        stubFor(getPath().willReturn(aResponse().withStatus(200).withBody("init")
                .withHeader("Last-Modified", "Fri, 4 Oct 2019 18:58:30 GMT")));

        var source = new HttpSource(client(), defaultURL(), CacheControlStrategy.ETAG);
        var loadedResource = source.load();
        assertThat(IOUtils.toString(loadedResource.getInputStream(), UTF_8)).isEqualTo("init");
        assertThat(loadedResource.getVersion()).isSameAs(SourceVersion.EMPTY);

        assertThat(source.loadIfModified(loadedResource.getVersion())).isNotNull();
    }

    private String defaultURL() {
        return url(PATH);
    }

    private MappingBuilder getPath() {
        return get(urlEqualTo(PATH));
    }

    private MappingBuilder headPath() {
        return head(urlEqualTo(PATH));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + wireMockServer.port() + path;
    }

    private CloseableHttpClient client() {
        return HttpClients.createMinimal();
    }

}
