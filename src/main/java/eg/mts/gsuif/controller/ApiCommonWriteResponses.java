package eg.mts.gsuif.controller;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiCommonResponses
@ApiResponses({
        @ApiResponse(responseCode = "415", description = "Unsupported Media Type", content = @Content(schema = @Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
})
public @interface ApiCommonWriteResponses {
}
