package org.oneticket.notifications

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Time is an injected port ([SVC-15]); tests replace it with `Clock.fixed(...)`. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
