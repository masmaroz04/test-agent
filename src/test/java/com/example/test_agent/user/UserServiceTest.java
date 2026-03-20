package com.example.test_agent.user;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserServiceTest {

    private final UserService userService = new UserService();

    @Test
    void shouldReturnSeedUsers() {
        List<User> users = userService.getAll();
        assertEquals(3, users.size());
        assertEquals(1L, users.get(0).id());
    }

    @Test
    void shouldThrowWhenUserMissing() {
        assertThrows(UserNotFoundException.class, () -> userService.getById(999L));
    }
}
