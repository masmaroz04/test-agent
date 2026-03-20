package com.example.test_agent.user;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final Map<Long, User> users = Map.of(
            1L, new User(1L, "Alice Johnson", "alice@example.com"),
            2L, new User(2L, "Bob Smith", "bob@example.com"),
            3L, new User(3L, "Charlie Brown", "charlie@example.com")
    );

    public List<User> getAll() {
        return users.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    public User getById(Long id) {
        User user = users.get(id);
        if (user == null) {
            throw new UserNotFoundException(id);
        }
        return user;
    }
}
