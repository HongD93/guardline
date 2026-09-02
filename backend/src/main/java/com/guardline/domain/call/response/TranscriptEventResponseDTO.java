package com.guardline.domain.call.response;

/**
 * 브라우저로 내려보내는 통화 스트림 이벤트.
 *
 * @param type       ready(업스트림 연결됨) / partial(진행 중 인식) / final(턴 확정) / closed / error
 * @param transcript 인식된 문장. 상태 이벤트에서는 null
 * @param turnOrder  AssemblyAI가 매기는 턴 순번. 상태 이벤트에서는 null
 * @param speaker    화자 분리 라벨(A, B ...). 화자 분리가 꺼져 있거나 상태 이벤트면 null
 * @param message    상태 또는 오류 설명. 전사 이벤트에서는 null
 */
public record TranscriptEventResponseDTO(
        String type,
        String transcript,
        Integer turnOrder,
        String speaker,
        String message
) {

    public static TranscriptEventResponseDTO partial(String transcript, Integer turnOrder, String speaker) {
        return new TranscriptEventResponseDTO("partial", transcript, turnOrder, speaker, null);
    }

    public static TranscriptEventResponseDTO finalTurn(String transcript, Integer turnOrder, String speaker) {
        return new TranscriptEventResponseDTO("final", transcript, turnOrder, speaker, null);
    }

    public static TranscriptEventResponseDTO ready() {
        return new TranscriptEventResponseDTO("ready", null, null, null, "업스트림 STT 연결됨");
    }

    public static TranscriptEventResponseDTO closed(String message) {
        return new TranscriptEventResponseDTO("closed", null, null, null, message);
    }

    public static TranscriptEventResponseDTO error(String message) {
        return new TranscriptEventResponseDTO("error", null, null, null, message);
    }
}
