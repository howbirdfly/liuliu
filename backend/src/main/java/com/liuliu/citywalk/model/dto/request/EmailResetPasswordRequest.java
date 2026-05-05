package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailResetPasswordRequest(
        @NotBlank(message = "email cannot be blank")
        @Email(message = "email format is invalid")
        String email,
        @NotBlank(message = "password cannot be blank")
        String password,
        @NotBlank(message = "code cannot be blank")
        String code
) {
}
