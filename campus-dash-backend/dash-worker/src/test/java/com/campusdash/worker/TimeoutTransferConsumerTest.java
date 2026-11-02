package com.campusdash.worker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TimeoutTransferConsumerTest {
    @Test void supports_legacy_numeric_and_new_string_identifiers() {
        assertEquals(9007199254740993L,TimeoutTransferConsumer.extractLong("{\"errandId\":9007199254740993}","errandId"));
        assertEquals(9007199254740993L,TimeoutTransferConsumer.extractLong("{ \"errandId\": \"9007199254740993\" }","errandId"));
    }
    @Test void malformed_empty_non_object_and_overflow_are_poison() {
        for(String body:new String[]{""," ","null","[]","{", "{}", "{\"errandId\":1.5}","{\"errandId\":9223372036854775808}"})
            assertThrows(IllegalArgumentException.class,()->TimeoutTransferConsumer.extractLong(body,"errandId"),body);
    }
}
