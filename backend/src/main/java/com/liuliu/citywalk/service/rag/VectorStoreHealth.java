package com.liuliu.citywalk.service.rag;

import java.util.List;

public record VectorStoreHealth(
        boolean enabled,
        boolean reachable,
        String provider,
        List<String> reasons
) {
}
