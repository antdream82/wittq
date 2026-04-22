<h1>TQQQ 200 이평선 & 변형 TQQQ 위젯 
<br>for 안드로이드용</h1><br>
<br><br>

<h2>__[소개글]__ </h2><br>
200티큐에 도움이 되길 바래요

<br><br>
<h2>__[레퍼런스]__ </h2><br>
<h3>TQQQ 200MA 투자 전략</h3>
200MA GC/DC 전략 <br>

<h3>TQQQ 200MA / QQQ 전략 </h3>
3/161MA, TQ200 전략 <br>

<h3>TQQQ 220MA / DIP 전략 </h3>
5/220MA, DIP 전략 <br>

<h3>로직 레퍼런스</h3>
호리오리<br>
<br><br>

<h3> 해당 위젯은 상황판처럼 출력 역할만 합니다</h3>

APK 빌드 : <br>
GitHub의 `Actions` 탭에서 `Build APK` 워크플로우를 실행하면 `app-release-apk` artifact가 생성됩니다.<br>
release APK는 고정 서명키로 빌드되므로, 이후 업데이트 설치가 가능합니다.<br>
<br>
GitHub Secrets 필요값:<br>
`ANDROID_KEYSTORE_BASE64`<br>
`KEYSTORE_PASSWORD`<br>
`KEY_ALIAS`<br>
`KEY_PASSWORD`<br>
<br>
첫 install은 기존 debug 서명 앱과 다르면 삭제 후 설치가 필요할 수 있습니다.<br>
그 다음부터는 같은 release 서명이라 업데이트 설치가 됩니다.
