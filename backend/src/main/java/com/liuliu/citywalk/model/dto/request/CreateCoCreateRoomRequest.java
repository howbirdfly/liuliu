package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.Valid;

public record CreateCoCreateRoomRequest(
        String roomCode,
        @Valid
        CoCreateRoomThemeRequest theme
) {
}
