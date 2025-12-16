package com.ecotrack.userservice.service;

import com.ecotrack.userservice.dto.AuthResponse;
import com.ecotrack.userservice.dto.LoginRequest;
import com.ecotrack.userservice.dto.RegisterRequest;
import com.ecotrack.userservice.dto.UserDTO;
import com.ecotrack.userservice.entity.User;
import com.ecotrack.userservice.repository.UserRepository;
import com.ecotrack.userservice.security.JwtUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Optional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil, AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
    }

    public AuthResponse register(RegisterRequest registerRequest) {
        System.out.println("=== REGISTER DEBUG ===");
        System.out.println("Registering user: " + registerRequest.getUsername());
        System.out.println("Email: " + registerRequest.getEmail());

        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            System.out.println("❌ Username already exists");
            return new AuthResponse(null, null, "Error: Username already exists");
        }
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            System.out.println("❌ Email already in use");
            return new AuthResponse(null, null, "Error: Email already in use");
        }

        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setEmail(registerRequest.getEmail());

        // Encoder le mot de passe
        String encodedPassword = passwordEncoder.encode(registerRequest.getPassword());
        user.setPassword(encodedPassword);
        user.setRole("USER");

        System.out.println("✅ Password encoded: " + encodedPassword.substring(0, 30) + "...");

        userRepository.save(user);
        System.out.println("✅ User saved to database");

        String token = jwtUtil.generateToken(user.getUsername());
        System.out.println("✅ Token generated: " + token.substring(0, 20) + "...");
        System.out.println("=== REGISTER COMPLETE ===");

        return new AuthResponse(token, user.getUsername(), "Registration successful");
    }

    public AuthResponse login(LoginRequest loginRequest) {
        System.out.println("\n=== DÉBUT LOGIN DEBUG ===");
        System.out.println("Username reçu: " + loginRequest.getUsername());
        System.out.println("Password reçu: " + loginRequest.getPassword());

        try {
            // 1. Vérifier si l'utilisateur existe
            Optional<User> userOptional = userRepository.findByUsername(loginRequest.getUsername());
            if (userOptional.isEmpty()) {
                System.out.println("❌ Utilisateur non trouvé dans la base");
                return new AuthResponse(null, null, "Error: User not found");
            }

            User user = userOptional.get();
            System.out.println("✅ Utilisateur trouvé: " + user.getUsername());
            System.out.println("🔐 Mot de passe stocké (hash): " + user.getPassword());
            System.out.println("📧 Email: " + user.getEmail());
            System.out.println("👤 Role: " + user.getRole());

            // 2. Vérifier manuellement le mot de passe avec BCrypt
            boolean passwordMatches = passwordEncoder.matches(
                    loginRequest.getPassword(),
                    user.getPassword()
            );

            System.out.println("🔍 Test BCrypt manuel: " + passwordMatches);

            if (!passwordMatches) {
                System.out.println("❌ Mot de passe incorrect (BCrypt échoué)");

                // Afficher des infos de debug sur le hash
                String storedPassword = user.getPassword();
                if (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$")) {
                    System.out.println("ℹ️ Format BCrypt détecté dans la base");
                } else {
                    System.out.println("⚠️ ATTENTION: Le mot de passe stocké n'a pas le format BCrypt!");
                    System.out.println("Format détecté: " + storedPassword.substring(0, Math.min(20, storedPassword.length())) + "...");
                }

                return new AuthResponse(null, null, "Error: Invalid password");
            }

            System.out.println("✅ Mot de passe BCrypt validé");

            // 3. Essayer avec AuthenticationManager d'abord
            try {
                System.out.println("🔄 Tentative avec AuthenticationManager...");
                Authentication authentication = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                loginRequest.getUsername(),
                                loginRequest.getPassword()
                        )
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                System.out.println("✅ AuthenticationManager réussi");
            } catch (Exception e) {
                System.out.println("⚠️ AuthenticationManager échoué, utilisation de la méthode manuelle");
                System.out.println("Erreur AuthenticationManager: " + e.getMessage());

                // Méthode manuelle de secours
                UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                        user.getUsername(),
                        user.getPassword(),
                        Collections.emptyList()
                );

                Authentication manualAuth = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                SecurityContextHolder.getContext().setAuthentication(manualAuth);
                System.out.println("✅ Authentication manuelle configurée");
            }

            // 4. Générer le token JWT
            String token = jwtUtil.generateToken(user.getUsername());
            System.out.println("✅ Token JWT généré: " + token.substring(0, 20) + "...");

            System.out.println("=== FIN LOGIN DEBUG - SUCCÈS ===");
            return new AuthResponse(token, user.getUsername(), "Login successful");

        } catch (Exception e) {
            System.out.println("💥 ERREUR EXCEPTION: " + e.getMessage());
            e.printStackTrace();
            return new AuthResponse(null, null, "Error: Login failed - " + e.getMessage());
        }
    }

    public Optional<User> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                if (!username.equals("anonymousUser")) {
                    return userRepository.findByUsername(username);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<UserDTO> getUserById(Long id) {
        return userRepository.findById(id)
                .map(user -> new UserDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getRole()
                ));
    }

    public boolean userExists(Long id) {
        return userRepository.existsById(id);
    }

    public Optional<UserDTO> getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(user -> new UserDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getRole()
                ));
    }
}