package badapodo.sugang.service.inmemory;

import java.time.DayOfWeek;
import java.time.LocalTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class InMemoryCourseTime {

    private final Long courseId;
    private final DayOfWeek dayOfWeek;
    private final LocalTime startTime;
    private final LocalTime endTime;

    public boolean overlaps(InMemoryCourseTime other) {
        if (dayOfWeek != other.dayOfWeek) {
            return false;
        }

        return startTime.isBefore(other.endTime)
                && other.startTime.isBefore(endTime);
    }
}
