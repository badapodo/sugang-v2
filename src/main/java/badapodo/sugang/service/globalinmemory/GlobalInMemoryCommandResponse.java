package badapodo.sugang.service.globalinmemory;

import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GlobalInMemoryCommandResponse {

    private final boolean acceptedByWriter;
    private final boolean timedOut;
    private final boolean commandIdConflict;
    private final UUID commandId;
    private final String message;
    private final GlobalInMemoryEnrollmentCommandResult result;

    public static GlobalInMemoryCommandResponse queueFull(UUID commandId) {
        return new GlobalInMemoryCommandResponse(
                false,
                false,
                false,
                commandId,
                "현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.",
                null
        );
    }

    public static GlobalInMemoryCommandResponse timeout(UUID commandId) {
        return new GlobalInMemoryCommandResponse(
                true,
                true,
                false,
                commandId,
                "수강신청 처리 응답 대기 시간이 초과되었습니다.",
                null
        );
    }

    public static GlobalInMemoryCommandResponse commandIdConflict(UUID commandId) {
        return new GlobalInMemoryCommandResponse(
                false,
                false,
                true,
                commandId,
                "동일한 commandId로 다른 요청 본문이 전송되었습니다.",
                null
        );
    }

    public static GlobalInMemoryCommandResponse completed(GlobalInMemoryEnrollmentCommandResult result) {
        return new GlobalInMemoryCommandResponse(
                true,
                false,
                false,
                result.commandId(),
                result.message(),
                result
        );
    }
}
