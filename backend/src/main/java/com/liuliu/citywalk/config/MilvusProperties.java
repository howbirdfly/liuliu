package com.liuliu.citywalk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private boolean enabled;
    private String uri = "http://127.0.0.1:19530";
    private String token = "root:Milvus";
    private String database = "default";
    private String collection = "citywalk_knowledge";
    private String vectorField = "embedding";
    private String contentField = "content";
    private String chunkIdField = "chunk_id";
    private String sourceIdField = "source_id";
    private String sourceTypeField = "source_type";
    private String titleField = "title";
    private String metadataField = "metadata";
    private int dimension = 1024;
    private int defaultTopK = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getVectorField() {
        return vectorField;
    }

    public void setVectorField(String vectorField) {
        this.vectorField = vectorField;
    }

    public String getContentField() {
        return contentField;
    }

    public void setContentField(String contentField) {
        this.contentField = contentField;
    }

    public String getChunkIdField() {
        return chunkIdField;
    }

    public void setChunkIdField(String chunkIdField) {
        this.chunkIdField = chunkIdField;
    }

    public String getSourceIdField() {
        return sourceIdField;
    }

    public void setSourceIdField(String sourceIdField) {
        this.sourceIdField = sourceIdField;
    }

    public String getSourceTypeField() {
        return sourceTypeField;
    }

    public void setSourceTypeField(String sourceTypeField) {
        this.sourceTypeField = sourceTypeField;
    }

    public String getTitleField() {
        return titleField;
    }

    public void setTitleField(String titleField) {
        this.titleField = titleField;
    }

    public String getMetadataField() {
        return metadataField;
    }

    public void setMetadataField(String metadataField) {
        this.metadataField = metadataField;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }
}
