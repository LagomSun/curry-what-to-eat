这个目录放你自己的签名文件 whattoeat-release.jks。

★ 签名私钥（.jks）永远不要提交到 GitHub！
★ 签名密码放在 local.properties（已被 .gitignore 排除）：
     keystore.storePassword=你的密码
     keystore.keyPassword=你的密码
   或用环境变量 WTE_KS_PASS / WTE_KEY_PASS。

第一次自己生成签名：
  keytool -genkeypair -v -keystore keystore/whattoeat-release.jks \
    -alias whattoeat -keyalg RSA -keysize 2048 -validity 10000

⚠ 注意：换了 .jks 之后，已装旧版的手机必须先卸载才能装新版（签名不一致）。
