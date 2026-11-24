# ReiAnix: desempenho de navegação — 2026-11-23

Escopo aprovado: técnicas **1, 2, 3, 4, 6, 7, 8, 10, 11, 13, 15, 16, 17**.
PR #428 foi rejeitada por piorar o desempenho: **não** reintroduzir seu
cálculo de thumbnails com `snapshotFlow` e **não** usar fade puro global.

## Mudanças implementadas / preservadas

| Técnica | Estratégia no ReiAnix |
|---|---|
| 1. Animações por tela | Motion policy direciona Detalhes locais/AniList para configurações próprias; restantes mantêm a animação original |
| 2. Não animar destino sem mudança | Ignora seleção repetida da mesma aba; `NavHost` suprime animação para a mesma entrada |
| 3. Entrar/Voltar | Especificações distintas de pop e forward preservam fade + escala |
| 4. Overrides por destino | `ReiAnixRouteAnimation` mantém mapeamento centralizado para detalhes/standard/none |
| 6. `graphicsLayer` | Preserva animação de abas internas e aplica microescala da barra apenas no desenho |
| 7. Evitar remeasure por frame | Barra inferior deixa de executar `expandVertically/shrinkVertically` durante a troca do `Scaffold`; libera espaço de detalhes imediatamente |
| 8. Springs | Microescala de ícones da barra inferior utiliza spring sem modificar tamanho/posicionamento de layout |
| 10. Identidade por tela | Mantém IDs por entrada do `NavHostController`; detalhe de recomendação continua a criar nova entrada |
| 11. Estado preservado por tela | Mantém `rememberSaveable`, `NavBackStackEntry`, `LazyListState.Saver` e ViewModels existentes |
| 13. Histórico por aba | Mantém `restoreState/saveState` nas abas principais; sem migração de Navigation Compose 2 para Navigation 3 |
| 15. Estados derivados | O shell deriva apenas rota/origem da entrada; trocar anime dentro de mesma rota não deve invalidar esses observadores |
| 16. JankStats | Registro de frames com identificador de rota, opt-in via propriedade `log.tag.ReiAnixJank` |
| 17. Macrobenchmark | Módulo `macrobenchmark` isolado, apenas para execução **manual em dispositivo real** com Biblioteca populada; não é acionado por workflows |

## Habilitar medição sem afetar o uso cotidiano

Em um dispositivo Android autorizado, antes de abrir o app:

```bash
adb shell setprop log.tag.ReiAnixJank DEBUG
adb shell am force-stop com.reianix.app
adb logcat -s ReiAnixJank:D
```

Apenas frames considerados *janky* pelo sistema serão registrados com estado
de rota quando disponível; não salvar relatórios silenciosamente nem enviar
métricas pela rede.

Para desabilitar:

```bash
adb shell setprop log.tag.ReiAnixJank INFO
adb shell am force-stop com.reianix.app
```

O benchmark AndroidX utiliza a variante isolada `benchmark`, não altera a assinatura ou o perfilamento do release oficial. Exige app instalado, configurado e Biblioteca com animes.
Não existe emulador ou workflow de instrumentação neste repositório.
Execute manualmente o módulo **somente num aparelho físico de testes**:

```bash
gradle -p android :macrobenchmark:connectedBenchmarkAndroidTest
```

Antes de comparar, reinstale duas versões em condições equivalentes e compare
`FrameTimingMetric` (p50/p90/p95 e frames lentos). A medição tem efeito
de instrumentação e depende de dispositivo, modo de energia e conteúdos.

## Riscos e verificações

- A barra inferior deixa de animar **a altura**; o efeito do próprio NavHost
  continua. Verificar que não existe salto, barra preta ou sobreposição.
- Detalhes mantêm fade+scale com amplitudes menores (0.97/0.985 no retorno);
  nunca aplicar o fade puro da PR rejeitada.
- Verificar **Voltar** em recomendações: anime A -> B -> C -> B -> A -> Biblioteca,
  e preservação de scroll/temporadas nas abas.
- Identidades, player, fontes, capas e tempo inicial dos metadados não mudam.
- Build de APK não demonstra ganho real. Sem comparar métricas físicas, não
  podemos declarar melhorias percentuais de FPS ou ausência total de travadas.
