# 官方固化签名证书 (Solidified Signing Keystore)

本项目已配置专属固定签名，确保云端 GitHub Actions CI 与本地编译生成的所有 APK 签名证书 100% 保持一致，支持后续所有新版本无缝覆盖升级（无需卸载、保留数据）。

## 证书信息
- **证书文件**: `app/keystore/enhucen.keystore`
- **所有者 (Subject)**: `CN=enhucen, O=Kgooer, C=CN`
- **密钥别名 (Alias)**: `enhucen`
- **密钥密码 (Password)**: `enhucen@kgooer.2026`
- **有效期**: 至 2054年03月08日 (28年)
- **SHA-256 指纹**: `DC:CD:4F:53:1B:B4:57:54:91:EC:3F:EE:41:FD:90:58:75:8C:1A:AA:03:C2:94:10:44:16:AA:C9:64:B3:16:E1`
