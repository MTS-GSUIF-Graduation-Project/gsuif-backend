package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.AuthResponse;
import eg.mts.gsuif.dto.LoginRequest;

public interface AuthService {
    AuthResponse login(LoginRequest loginRequest);
}
