package com.example.test_agent.user;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    @Test
    void shouldReturnAllUsers() {
        List<User> mockUsers = List.of(
                new User(1L, "Alice Johnson", "alice@example.com"),
                new User(2L, "Bob Smith", "bob@example.com")
        );
        when(userRepository.findAll()).thenReturn(mockUsers);

        List<User> users = userService.getAll();
        assertEquals(2, users.size());
        assertEquals("Alice Johnson", users.get(0).getFullName());
    }

    @Test
    void shouldThrowWhenUserMissing() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> userService.getById(999L));
    }
}
