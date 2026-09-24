package com.guardline.domain.risk.client;

import java.util.regex.Pattern;

/**
 * S4·S5의 원문 인용에 실제 요청이 들어 있는지 보수적으로 확인한다.
 * 이 검사는 사기 여부나 화자 신원을 증명하지 않으며, 우회 표현을 놓칠 수 있다.
 */
final class ActionEvidence {
    private static final Pattern DENIAL = Pattern.compile(
            "(설치|다운로드|클릭|입력|알려|송금|이체|입금|인출|전달|보내|구매|충전).{0,16}"
                    + "(지\\s*마|지\\s*않|하시면\\s*안|하면\\s*안|필요.{0,4}없|요구하지|묻지|않습니다)");
    private static final Pattern REQUEST = Pattern.compile(
            "(해|하|보내|알려|눌러|열어|불러|넘겨|옮겨|말해|적어).{0,10}"
                    + "(주세|줘|줄|주면|주시면|세요|십시오|해야|부탁|주시)|"
                    + "(설치|다운로드|클릭|입력|송금|이체|입금|인출|전달|구매|충전).{0,8}(하세요|해야|부탁|하셔야)");
    private static final Pattern ACCESS = Pattern.compile(
            "(앱|어플|프로그램).{0,20}(설치|다운로드)|"
                    + "링크.{0,20}(클릭|누르|눌러|열어|접속)|"
                    + "(인증번호|OTP|비밀번호|카드번호).{0,20}(알려|불러|입력|보내|말해|적어)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_ACTION = Pattern.compile(
            "송금|이체|입금|인출|현금.{0,16}전달|(상품권|가상자산|코인).{0,16}(구매|충전)|"
                    + "(돈|원|금액|전액|계좌).{0,25}(보내|옮겨|넘겨)");
    private static final Pattern ACCOUNT = Pattern.compile("예금주|계좌\\s*번호");
    private static final Pattern BALANCE = Pattern.compile("잔액|입금.{0,6}(완료|확인)|송금.{0,6}완료");
    private static final Pattern AMOUNT = Pattern.compile("[0-9일이삼사오육칠팔구십백천만억]+\\s*원");

    private ActionEvidence() {}

    static boolean supports(String id, String evidence) {
        if (!"S4".equals(id) && !"S5".equals(id)) {
            return true;
        }
        if (DENIAL.matcher(evidence).find()) {
            return false;
        }
        if ("S4".equals(id)) {
            return ACCESS.matcher(evidence).find() && REQUEST.matcher(evidence).find();
        }
        return (MONEY_ACTION.matcher(evidence).find() && REQUEST.matcher(evidence).find())
                || (ACCOUNT.matcher(evidence).find() && AMOUNT.matcher(evidence).find()
                    && !BALANCE.matcher(evidence).find());
    }
}
