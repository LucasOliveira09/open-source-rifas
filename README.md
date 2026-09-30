# Rifas Iracema

Aplicação web para organizar e comprar rifas da escola Iracema.

## Estrutura

- `frontend/`: aplicação Angular.
- `backend/`: API Java com Spring Boot.

Versões iniciais: Angular 21, Spring Boot 4.1.1 e Java 21.

## Requisitos

- Node.js 24 e npm para o frontend.
- Java 21 ou superior para o backend.

## Executar localmente

Prepare o arquivo de configuração local uma vez:

```powershell
Copy-Item .env.example .env
```

Abra `.env` e preencha `MERCADO_PAGO_ACCESS_TOKEN` e `MERCADO_PAGO_WEBHOOK_SECRET` com as credenciais de teste da conta Mercado Pago. O arquivo `.env` é ignorado pelo Git. O `.env.example` contém apenas os nomes das variáveis.

Inicie o PostgreSQL local:

```powershell
docker compose up -d
```

No terminal 1, inicie a API:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

No terminal 2, inicie a aplicação web:

```powershell
cd frontend
npm start
```

O Angular ficará disponível em `http://localhost:4200`. A API Spring Boot usa `http://localhost:8080`.

O banco é preparado automaticamente pelo Flyway quando a API iniciar. A primeira migração cria a rifa e os números de 1 a 100. O preço começa sem configuração, então o checkout permanece desabilitado até a escola definir o valor. Os prêmios também serão exibidos assim que forem cadastrados.

O Spring Boot lê as variáveis do `.env` ao iniciar, e o Docker Compose usa o mesmo arquivo para o banco. O código Pix vence após 24 horas por padrão; ajuste `PIX_EXPIRATION` no formato ISO 8601, de 30 minutos a 30 dias.

No painel do Mercado Pago, configure o evento **Order (Mercado Pago)** para `https://<seu-dominio>/api/webhooks/mercadopago` e copie o segredo gerado para `MERCADO_PAGO_WEBHOOK_SECRET`. Em desenvolvimento, a URL também precisa estar publicamente acessível por HTTPS. O backend confirma a assinatura e consulta a order ao Mercado Pago; só marca a compra e os números como pagos quando o Pix estiver aprovado pelo valor integral. Orders canceladas, expiradas ou falhas liberam os números após a confirmação do estado final. Números pendentes não são liberados apenas porque passou o prazo local, para evitar vender uma rifa cujo Pix tenha sido pago com Webhook atrasado.

## Escopo em definição

O fluxo e as decisões pendentes estão em [docs/especificacao.md](docs/especificacao.md). A tela pública, as consultas da rifa, o formulário de compra e a integração Pix estão implementados. O checkout permanece desabilitado até que o preço seja definido e as credenciais do Mercado Pago sejam configuradas. Os prêmios ainda precisam ser cadastrados; não há painel administrativo nesta primeira etapa.
