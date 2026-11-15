# 번들 폰트 출처와 라이선스

앱에 넣은 폰트는 모두 SIL Open Font License 1.1 계열이다. 폰트 파일은 원본 그대로이며(글리프 수정·서브셋 없음), 파일 이름만 Android 리소스 규칙(소문자·밑줄)에 맞게 바꿨다. 라이선스 전문은 이 폴더의 두 텍스트 파일에 있고, 이 폴더는 APK의 assets에 함께 들어간다(OFL 2항: 사본마다 저작권 고지와 라이선스 포함).

| 앱 리소스 | 원본 파일 | 용도 |
|---|---|---|
| `res/font/gmarket_sans_bold.ttf` | `GmarketSansTTFBold.ttf` | 패널 제목, 큰 수치 |
| `res/font/pretendard_regular.otf` | `public/static/Pretendard-Regular.otf` | 본문 |
| `res/font/pretendard_bold.otf` | `public/static/Pretendard-Bold.otf` | 탭 라벨, 버튼, 강조 본문 |

## Pretendard 1.3.9

- 내려받은 곳: https://github.com/orioncactus/pretendard/releases/download/v1.3.9/Pretendard-1.3.9.zip (GitHub API `releases/latest`의 tag `v1.3.9`)
- zip SHA-256: `04be351a74d6bf7d60c480a3087e51d185485d35a52023142af1df19eb8c428a`
- 라이선스: SIL OFL 1.1, Reserved Font Name "Pretendard". 저작권 Kil Hyung-jin. 베이스 폰트(Source Han Sans/Adobe, Inter, M PLUS 1)의 고지 포함.
- 라이선스 원문: https://raw.githubusercontent.com/orioncactus/pretendard/main/LICENSE 를 그대로 `Pretendard-LICENSE.txt`로 저장했다. zip 안의 `LICENSE.txt`는 같은 OFL 본문이지만 베이스 폰트 고지가 빠져 있어, 더 완전한 저장소 원문을 택했다.
- 파일 SHA-256: Regular `3ffbacde6ab8411f1d2db54bb9b1f0b3ee2a738932033722cf0388c06aed1c93`, Bold `2e91915fab54df71cc9598ebf608b2bdb54c6fe3c066ac61dff0bc44fca71cc7`

## Gmarket Sans (TTF Bold, Version 1.000)

- 내려받은 곳: https://corp.gmarket.com/fonts/GmarketSansTTF.zip (공식 폰트 페이지 https://corp.gmarket.com/fonts/ 의 다운로드 링크)
- zip SHA-256: `a2cc0ab9eb3bc868a6f2affd89fa1d4718cb6e1226dcb695b05d0d2ff417ae02`
- 공식 페이지 문구: "Gmarket Sans는 'SIL Open Font License'에 따라 개인 또는 기업이 영리적, 비영리적 목적으로 자유롭게 사용할 수 있습니다."
- zip에는 별도 라이선스 파일이 없다. 대신 폰트 파일 자체의 name 테이블(nameID 13, 영문·한글)에 라이선스 전문이 들어 있어, 이를 그대로 추출해 `GmarketSans-LICENSE.txt`로 저장했다. 저작권 eBay Korea Co., Ltd.(2019), Reserved Font Name "Gmarket Sans Font".
- 표준 OFL 1.1과 다른 점: 6항(이 폰트를 쓴 제작물을 저작권자가 홍보에 활용할 수 있고, 원하지 않으면 거부 의사를 표시)이 추가되어 있다. 한글 요약은 "상표권 이용 불가"를 명시하므로 앱 화면·문서에서 폰트 이름을 상표처럼 쓰지 않는다.
- 파일 SHA-256: `ff7c354dd1a324e4cecc1223c4f71e74fa81be7027e0c7f6324c475909cacefc`

확인일: 2026-11-15.
