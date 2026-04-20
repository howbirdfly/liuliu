package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record JoinCoCreateRoomRequest(
        @NotBlank(message = "room_code_required")
        String roomCode
) {
}
