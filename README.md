# GodHand-CRF1-RECOMP

Projeto experimental para investigar a adaptação do God Hand Recompiled para Android ARM64.

## Estado atual

A estrutura Android inicial está sendo preparada para validar a cadeia de compilação Java/Gradle + Android NDK e gerar um APK de diagnóstico. **Isso ainda não é um port jogável** e não contém o jogo, ISO, ELF, overlays ou assets protegidos por direitos autorais.

O objetivo é estudar e adaptar o código público do projeto upstream [LasVola/godhand-recomp](https://github.com/LasVola/godhand-recomp), respeitando a GPL-3.0 e as licenças das dependências. O usuário deve fornecer sua própria cópia legítima do jogo quando uma etapa realmente exigir os dados originais.

## Compilação

O workflow `Android ARM64` compila o APK de diagnóstico e verifica se a biblioteca nativa `libgodhand_android.so` foi empacotada em `arm64-v8a`. A presença dessa biblioteca confirma somente a cadeia de build inicial, não a compatibilidade do jogo.
