package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomSpringELParserTest {

    @Test
    void method_parameter_name과_record_accessor를_사용해_dynamic_key를_만든다() {
        String result = CustomSpringELParser.parse(
                new String[]{"input"},
                new Object[]{new Input(1L, "session-1")},
                "#input.performanceId() + ':' + #input.queueSessionId()"
        );

        assertThat(result).isEqualTo("1:session-1");
    }

    private record Input(Long performanceId, String queueSessionId) {
    }
}
