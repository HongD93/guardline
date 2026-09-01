package com.guardline.domain.call.client;

import com.guardline.domain.call.response.TranscriptEventResponseDTO;

/** 업스트림 STT가 만들어낸 이벤트를 받아 브라우저 쪽으로 넘기는 쪽이 구현한다. */
public interface UpstreamListener {

    void onEvent(TranscriptEventResponseDTO event);
}
