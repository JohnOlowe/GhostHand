package com.example.agpproject;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MainActivityTest {
    @Test
    public void greetingIsBuiltFromTheArgument() {
        assertEquals("Hello, AGP!", MainActivity.greeting("AGP"));
    }
}
