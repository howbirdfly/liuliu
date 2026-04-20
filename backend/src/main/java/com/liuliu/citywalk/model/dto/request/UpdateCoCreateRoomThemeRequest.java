package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.Valid;

public record UpdateCoCreateRoomThemeRequest(
        @Valid
        CoCreateRoomThemeRequest theme
) {
}
