package com.jobai.infrastructure.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.jobai.common.JobAiProperties;
import com.jobai.common.JobAiProperties.RetrievalProperties;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@com.jobai.common.ConditionalOnRagEnabled
public class ElasticsearchConfig {

    @Bean
    public RestClient restClient(JobAiProperties properties) {
        JobAiProperties.ElasticsearchProperties es = properties.getElasticsearch();

        // credentials
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(es.getUsername(), es.getPassword()));

        return RestClient.builder(new HttpHost(es.getHost(), es.getPort(), es.getScheme()))
                .setHttpClientConfigCallback(hcb -> {
                    hcb.setDefaultCredentialsProvider(credentialsProvider);
                    if (es.getCaCertificate() != null && !es.getCaCertificate().isBlank()) {
                        hcb.setSSLContext(buildSslContext(es.getCaCertificate()));
                    }
                    // Otherwise retain the platform trust store and default hostname verification.
                    return hcb;
                })
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    @Bean
    public RetrievalProperties retrievalProperties(JobAiProperties props) {
        return props.getRetrieval();
    }

    static SSLContext buildSslContext(String certificatePath) {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            try (var in = Files.newInputStream(Path.of(certificatePath))) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
                if (certificates.isEmpty()) throw new IllegalArgumentException("CA certificate file is empty");
                int index = 0;
                for (var certificate : certificates) trustStore.setCertificateEntry("es-ca-" + index++, certificate);
            }
            return SSLContexts.custom().loadTrustMaterial(trustStore, null).build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load Elasticsearch CA certificate", e);
        }
    }

}
