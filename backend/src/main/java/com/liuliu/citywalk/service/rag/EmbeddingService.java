package com.liuliu.citywalk.service.rag;

import java.util.List;

public interface EmbeddingService {

    String provider();

    boolean isConfigured();

    List<Float> embed(String text);

    List<List<Float>> embedAll(List<String> texts);
}
