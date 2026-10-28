# GodHand-CRF1-RECOMP

Projeto experimental de port do [God Hand Recompiled oficial](https://github.com/LasVola/godhand-recomp) para Android ARM64.

## Estado atual

O projeto avançou além do launcher de diagnóstico:

- Launcher Android em Kotlin com seleção de ISO via Storage Access Framework e permissão persistente de leitura.
- Log persistente em armazenamento privado, Logcat e exportação do diagnóstico.
- Biblioteca JNI libgodhand_android.so ligada ao núcleo ps2_runtime do repositório oficial.
- Build Android usa o NDK e a camada SSE2NEON para adaptar intrínsecos SSE do runtime à arquitetura ARM64.
- Ao tocar em **Jogar**, o código nativo lê a ISO9660 pelo descritor de arquivo, verifica o descritor primário, o diretório raiz, SYSTEM.CNF e SLUS_215.03, e informa se o disco parece ser a versão esperada.
- O runtime oficial é fixado no commit 14e956a1274332440273845fbab2c1f1e30c4a5a para builds reproduzíveis.

**Ainda não é uma versão jogável.** O núcleo genérico PS2 está ligado, mas falta gerar e ligar ao APK o código C++ específico de God Hand, junto das dependências/recursos extraídos necessários. O projeto oficial é uma recompilação estática: escolher uma ISO no Android não gera automaticamente esse código e não inicia o jogo. O estado atual não deve ser confundido com a integração completa ou com um boot bem-sucedido.

## Referências técnicas

- Projeto oficial God Hand Recompiled: https://github.com/LasVola/godhand-recomp
- CMake principal oficial: https://github.com/LasVola/godhand-recomp/blob/14e956a1274332440273845fbab2c1f1e30c4a5a/CMakeLists.txt
- Runtime PS2 oficial: https://github.com/LasVola/godhand-recomp/tree/14e956a1274332440273845fbab2c1f1e30c4a5a/runtime
- CMake do runtime oficial: https://github.com/LasVola/godhand-recomp/blob/14e956a1274332440273845fbab2c1f1e30c4a5a/runtime/ps2xRuntime/CMakeLists.txt
- SSE2NEON: https://github.com/DLTcollab/sse2neon

O CMake Android importa somente o subdiretório runtime/ do projeto oficial, e não o projeto desktop completo, pois este exige arquivos C++ gerados específicos do jogo que ainda não estão presentes neste repositório.

## ISO e direitos autorais

Use somente sua própria cópia legítima da versão USA exigida pelo projeto upstream (God Hand, serial SLUS-21503). Este repositório não inclui ISO, ELF, código gerado, overlays, músicas ou outros dados do jogo.

O núcleo de runtime upstream é licenciado sob GPL-3.0. Consulte a licença e os avisos de terceiros antes de distribuir builds modificadas.

## Build

O workflow Android ARM64 compila o APK debug com Java, Android SDK, NDK e CMake, verifica a biblioteca ARM64 e publica o artefato GodHand-CRF1-Android-ARM64-debug. A compilação confirma apenas que o código incluído foi compilado; não prova que o jogo já inicializa.
