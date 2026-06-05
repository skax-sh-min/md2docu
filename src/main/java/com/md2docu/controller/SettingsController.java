package com.md2docu.controller;

import com.md2docu.model.UserSettings;
import com.md2docu.service.UserSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserSettingsService settingsService;

    public SettingsController(UserSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<UserSettings> get() {
        return ResponseEntity.ok(settingsService.get());
    }

    @PutMapping
    public ResponseEntity<UserSettings> save(@RequestBody UserSettings settings) throws IOException {
        return ResponseEntity.ok(settingsService.save(settings));
    }

    @PostMapping("/reset")
    public ResponseEntity<UserSettings> reset() throws IOException {
        return ResponseEntity.ok(settingsService.reset());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIo(IOException e) {
        return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
    }
}
