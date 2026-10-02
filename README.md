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

Abra `.env` e preencha `MERCADO_PAGO_ACCESS_TOKEN` com uma credencial `APP_USR` compatível com Checkout Transparente / Orders. Para produção, use a conta vendedora; para sandbox, use um usuário de teste com sua credencial `APP_USR`. Credenciais antigas com prefixo `TEST-` não são compatíveis com essa API. Veja a [referência oficial de criação de orders](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/create-order/post).

Preencha também `MERCADO_PAGO_WEBHOOK_SECRET` com o segredo de assinatura gerado no painel Webhooks da mesma integração. As duas variáveis são necessárias para habilitar o checkout. O arquivo `.env` é ignorado pelo Git. O `.env.example` contém apenas instruções e valores de desenvolvimento. Reinicie o backend depois de atualizar as credenciais.

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

O banco é preparado automaticamente pelo Flyway quando a API iniciar. As migrações criam a rifa e seus números de 1 a 100. Cada número custa R$ 5,00. Os prêmios também serão exibidos assim que forem cadastrados.

O Spring Boot lê as variáveis do `.env` ao iniciar, e o Docker Compose usa o mesmo arquivo para o banco. O código Pix vence após 24 horas por padrão; ajuste `PIX_EXPIRATION` no formato ISO 8601, de 30 minutos a 30 dias.

O comprador informa somente nome e telefone. O backend utiliza o e-mail fictício definido em `PIX_BUYER_EMAIL` no `.env` (padrão `rifas@example.com`), armazena esse valor na compra e o envia como `payer.email` na criação do Pix. Em sandbox, configure um endereço com domínio `@testuser.com`, conforme a [referência do Mercado Pago](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/create-order/post). A aceitação do domínio e do conteúdo do e-mail é validada pelo provedor. Reinicie o backend depois de alterar esse valor. A aplicação não possui envio de e-mails.

No painel do Mercado Pago, configure o evento **Order (Mercado Pago)** para `https://<seu-dominio>/api/webhooks/mercadopago` e copie o segredo gerado para `MERCADO_PAGO_WEBHOOK_SECRET`. Em desenvolvimento, a URL também precisa estar publicamente acessível por HTTPS. O backend confirma a assinatura e consulta a order ao Mercado Pago; só marca a compra e os números como pagos quando o Pix estiver aprovado pelo valor integral. Orders canceladas, expiradas ou falhas liberam os números após a confirmação do estado final. Números pendentes não são liberados apenas porque passou o prazo local, para evitar vender uma rifa cujo Pix tenha sido pago com Webhook atrasado.

## Escopo em definição

O contrato da criação do Pix, o recebimento de webhooks e a comparação com a documentação oficial estão em [docs/mercado-pago-pix-webhooks.md](docs/mercado-pago-pix-webhooks.md).

O fluxo e as decisões pendentes estão em [docs/especificacao.md](docs/especificacao.md). A tela pública, as consultas da rifa, o formulário de compra e a integração Pix estão implementados. O checkout permanece desabilitado até que as credenciais do Mercado Pago sejam configuradas. Os prêmios ainda precisam ser cadastrados; não há painel administrativo nesta primeira etapa.

## Implantar em VPS com acesso Tailscale

A pilha de produção está em `compose.vps.yaml`. Ela mantém PostgreSQL e API sem portas publicadas; somente o Nginx fica ligado a `127.0.0.1:8088` na VPS. O Tailscale Serve fornece HTTPS e acesso privado aos dispositivos autorizados na tailnet.

Na VPS Linux, instale Docker com o plugin Compose e Tailscale, conecte a VPS à mesma tailnet e clone este repositório. No diretório do projeto:

```bash
cp .env.example .env.vps
```

Edite `.env.vps` e defina uma senha forte para `POSTGRES_PASSWORD`. O preço da rifa é R$ 5,00 por número. Enquanto as credenciais do Mercado Pago estiverem vazias, a página abre, mas o checkout permanece desativado.

Construa e inicie os serviços:

```bash
docker compose --env-file .env.vps -f compose.vps.yaml up -d --build
```

Configure o Tailscale Serve na VPS:

```bash
sudo tailscale serve 8088
```

O comando informa o endereço HTTPS `.ts.net` privado para abrir nos dispositivos conectados à tailnet. O HTTPS do Serve precisa estar habilitado nas configurações da tailnet. Para verificar serviços e logs:

```bash
docker compose --env-file .env.vps -f compose.vps.yaml ps
docker compose --env-file .env.vps -f compose.vps.yaml logs -f backend frontend
```

Os dados do PostgreSQL ficam no volume Docker `rifas-vps-data`. Para atualizar, obtenha a versão nova do repositório e repita o comando `up -d --build`. Para voltar à versão anterior, restaure o código anterior e repita o comando; o volume do banco é preservado.

**Atenção ao webhook de pagamentos:** Tailscale Serve é privado, então o Mercado Pago não consegue acessar por ele. Para habilitar pagamentos reais, o endpoint `/api/webhooks/mercadopago` precisa de uma URL que o Mercado Pago possa alcançar pela internet com HTTPS. Não habilite Tailscale Funnel para a aplicação inteira sem decidir que ela deve ficar pública; ele abre acesso pela internet. Enquanto essa rota externa não for definida, mantenha o checkout desativado.
