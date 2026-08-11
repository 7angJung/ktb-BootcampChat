package com.ktb.chatapp.security;

import com.ktb.chatapp.model.User;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authentication principal that keeps the user data already loaded for password authentication.
 */
@Getter
public final class AuthenticatedUserPrincipal implements UserDetails {

    private final String id;
    private final String name;
    private final String email;
    private final String password;
    private final String profileImage;

    private AuthenticatedUserPrincipal(
            String id,
            String name,
            String email,
            String password,
            String profileImage) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.profileImage = profileImage;
    }

    public static AuthenticatedUserPrincipal from(User user) {
        return new AuthenticatedUserPrincipal(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPassword(),
                user.getProfileImage()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getUsername() {
        return email;
    }
}
