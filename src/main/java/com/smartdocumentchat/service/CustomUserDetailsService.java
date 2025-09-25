package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user by username: {}", username);

        // Try to find by username first
        Optional<User> userOpt = userRepository.findByUsername(username);

        // If not found, try by email
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(username);
        }

        if (userOpt.isEmpty()) {
            log.warn("User not found: {}", username);
            throw new UsernameNotFoundException("משתמש לא נמצא: " + username);
        }

        User user = userOpt.get();

        // Check if user is active
        if (!user.getActive()) {
            log.warn("User is not active: {}", username);
            throw new UsernameNotFoundException("משתמש לא פעיל: " + username);
        }

        // Check if password hash exists
        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            log.warn("User has no password set: {}", username);
            throw new UsernameNotFoundException("משתמש ללא סיסמה: " + username);
        }

        log.info("User loaded successfully: {} (ID: {})", user.getUsername(), user.getId());

        return new CustomUserPrincipal(user);
    }

    /**
     * Custom UserDetails implementation for our User entity
     */
    public static class CustomUserPrincipal implements UserDetails {
        private final User user;

        public CustomUserPrincipal(User user) {
            this.user = user;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            // For now, all users have basic USER role
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

            // TODO: In the future, we can add admin roles based on user properties
            // if (user.isAdmin()) {
            //     authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            // }

            return authorities;
        }

        @Override
        public String getPassword() {
            return user.getPasswordHash();
        }

        @Override
        public String getUsername() {
            return user.getUsername();
        }

        @Override
        public boolean isAccountNonExpired() {
            return true; // We don't have account expiration logic yet
        }

        @Override
        public boolean isAccountNonLocked() {
            return user.getActive(); // Use active flag as lock status
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true; // We don't have password expiration logic yet
        }

        @Override
        public boolean isEnabled() {
            return user.getActive();
        }

        // Custom getter for our User entity
        public User getUser() {
            return user;
        }

        public Long getUserId() {
            return user.getId();
        }

        public String getEmail() {
            return user.getEmail();
        }

        public String getFullName() {
            return user.getFullName();
        }

        @Override
        public String toString() {
            return String.format("CustomUserPrincipal{username='%s', userId=%d, active=%s}",
                    user.getUsername(), user.getId(), user.getActive());
        }
    }
}