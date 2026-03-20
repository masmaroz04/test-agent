package com.example.test_agent.user;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User id " + id + " not found");
    }
}
