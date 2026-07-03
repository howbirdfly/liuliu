package com.liuliu.citywalk.service;

@FunctionalInterface
public interface ThemeStreamListener {

    void onContentDelta(String delta);
}
