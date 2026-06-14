package com.liuliu.citywalk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "liuliu.rag")
public class RagProperties {

    private boolean enabled = true;
    private boolean rerankEnabled = true;
    private int rerankCandidateMultiplier = 4;
    private int rerankCandidateMaxTopK = 20;
    private boolean hybridKeywordRecallEnabled = true;
    private int hybridKeywordPerVariantLimit = 3;
    private int hybridKeywordMaxVariants = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public int getRerankCandidateMultiplier() {
        return rerankCandidateMultiplier;
    }

    public void setRerankCandidateMultiplier(int rerankCandidateMultiplier) {
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
    }

    public int getRerankCandidateMaxTopK() {
        return rerankCandidateMaxTopK;
    }

    public void setRerankCandidateMaxTopK(int rerankCandidateMaxTopK) {
        this.rerankCandidateMaxTopK = rerankCandidateMaxTopK;
    }

    public boolean isHybridKeywordRecallEnabled() {
        return hybridKeywordRecallEnabled;
    }

    public void setHybridKeywordRecallEnabled(boolean hybridKeywordRecallEnabled) {
        this.hybridKeywordRecallEnabled = hybridKeywordRecallEnabled;
    }

    public int getHybridKeywordPerVariantLimit() {
        return hybridKeywordPerVariantLimit;
    }

    public void setHybridKeywordPerVariantLimit(int hybridKeywordPerVariantLimit) {
        this.hybridKeywordPerVariantLimit = hybridKeywordPerVariantLimit;
    }

    public int getHybridKeywordMaxVariants() {
        return hybridKeywordMaxVariants;
    }

    public void setHybridKeywordMaxVariants(int hybridKeywordMaxVariants) {
        this.hybridKeywordMaxVariants = hybridKeywordMaxVariants;
    }
}
