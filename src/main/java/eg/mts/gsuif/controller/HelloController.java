package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public ResponseEntity<ApiResponse<String>> hello() {
        return ResponseEntity.ok(ApiResponse.success("Hello from GSUIF!", "Success"));

    }
}
