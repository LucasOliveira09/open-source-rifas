# Verificação local do Pix — 02/10/2026

## Resultado

A aplicação foi iniciada e a compra foi exercitada em Chrome com uma tentativa de R$ 5,00. **O Mercado Pago recusou a criação da order; nenhum QR Code foi gerado nessa tentativa.**

Resposta direta do provedor:

```json
{
  "status": 403,
  "code": "PA_UNAUTHORIZED_RESULT_FROM_POLICIES",
  "blocked_by": "PolicyAgent",
  "message": "At least one policy returned UNAUTHORIZED."
}
```

A credencial local tem formato `TEST-`. Ela foi aceita por `GET /users/me` e pela consulta da API de Payments, mas recusada tanto pela criação quanto pela consulta da API de Orders. Não é possível concluir o motivo exato da política de autorização somente com essa resposta. A [documentação da API utilizada](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/create-order/post) orienta usar credenciais da conta vendedora para produção ou de um usuário de teste para sandbox, com formato `APP_USR`; as credenciais antigas `TEST-` não são compatíveis com Orders.

`MERCADO_PAGO_WEBHOOK_SECRET` está vazio no `.env`. A aplicação principal mantém o checkout desabilitado e retorna 503 ao tentar iniciar uma compra nessa configuração.

## Testes executados

| Verificação | Resultado |
| --- | --- |
| Testes Angular | 7 aprovados |
| Testes Spring Boot/JUnit, com PostgreSQL | 15 aprovados |
| Build de produção Angular | Aprovado |
| Empacotamento do backend com Java 21 | Aprovado |
| Chrome: página, 100 números e preço de R$ 5,00 | Aprovado; sem exceções JavaScript |
| Chrome em 1440px e 375px: imagens, seleção, total e limpeza | Aprovado; sem rolagem horizontal ou requisições falhas |
| API: e-mail inválido, número fora do intervalo e números repetidos | 400 |
| API: compra inexistente | 404 |
| API: webhook sem assinatura | 401 |
| Compra pelo frontend usando a API real do Mercado Pago | 502 na aplicação; 403 no provedor |
| PostgreSQL após a recusa | Compra FAILED e número AVAILABLE, sem reserva ativa |

Os testes automatizados cobrem reserva, cálculo do preço no servidor, dados do comprador, conflito por número reservado, confirmação de Pix integral, recusa de valor ou método incorreto, assinatura HMAC, consulta ao provedor, idempotência e liberação após cancelamento. **Confirmação de pagamento usa dados simulados nesses testes; não houve transferência Pix nem recebimento de webhook real pela internet.**

Para isolar a tentativa de compra, foi usada uma segunda API na porta 8081 com banco exclusivo e segredo de assinatura exclusivamente de teste, encerrada ao terminar a verificação. Esse segredo não foi colocado no `.env` e não substitui o segredo gerado pelo Mercado Pago.

## Correção encontrada

O frontend falhava ao renderizar o preço com locale `pt-BR` sem os dados desse locale registrados. O registro foi adicionado em `frontend/src/app/app.config.ts`; os testes confirmaram a renderização do preço após a correção.

## Serviços locais

- Frontend: `http://localhost:4200`.
- API principal: `http://localhost:8080`.
- PostgreSQL 17: `127.0.0.1:5433`, banco `rifas_iracema`.
- Banco isolado da verificação: `rifas_verificacao_20261002`.

O Docker Desktop não iniciou por falha de sockets locais. Foi iniciado um PostgreSQL portátil separado, com dados persistidos em `.local/postgres17-data`, ignorados pelo Git. O volume do banco Docker foi preservado. A base portátil é uma base local nova, sem dados importados da VPS ou do volume Docker.

Para reiniciar esta configuração local enquanto o Docker estiver indisponível, execute em terminais separados, a partir da raiz do repositório:

```powershell
# Terminal 1: PostgreSQL portátil instalado nesta máquina
node.exe "$env:LOCALAPPDATA\RifasRuntime\pg-run.mjs"
```

```powershell
# Terminal 2: backend já empacotado, banco na porta 5433
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5433/rifas_iracema'
$env:SERVER_PORT = '8080'
Set-Location backend
& 'C:\Program Files\Java\jdk-21\bin\java.exe' -jar target/rifas-0.0.1-SNAPSHOT.jar
```

```powershell
# Terminal 3: frontend
Set-Location frontend
npm.cmd start -- --host 127.0.0.1
```

## Para concluir a verificação externa

1. Configurar uma credencial `APP_USR` compatível com Orders no `.env`.
2. Preencher o segredo verdadeiro de assinatura e reiniciar o backend.
3. Repetir uma compra e conferir QR Code, Copia e Cola e persistência no banco.
4. Configurar uma URL HTTPS pública para `/api/webhooks/mercadopago` no evento Order. `localhost` e Tailscale Serve privado não permitem que o Mercado Pago entregue notificações externas.
5. Validar a confirmação em sandbox apropriado ou acompanhar um pagamento real autorizado, verificando que compra e número passam a PAID somente após confirmação do provedor.

Os detalhes locais da execução foram salvos em `.local/pix-verification.json` e `.local/pix-provider-diagnostic.json`, sem tokens ou segredo de assinatura.
