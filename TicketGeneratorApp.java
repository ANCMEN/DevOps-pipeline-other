import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Веб-додаток "Генератор екзаменаційних білетів".
 * Відкрити: http://localhost:8080
 */
public class TicketGeneratorApp {

    /** Модуль (блок) питань: діапазон номерів from .. from+size-1 */
    record Module(int number, int from, int size) {}

    /** Білет: для кожного модуля - список номерів питань */
    record Ticket(int number, List<List<Integer>> parts) {}

    // ---------------------------------------------------------------- main
    public static void main(String[] args) throws IOException {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", TicketGeneratorApp::handle);
        server.start();
        System.out.println("Сервер запущено: http://localhost:" + port);
    }

    // ------------------------------------------------------------ HTTP
    static void handle(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "<h1>404</h1>");
            return;
        }
        Map<String, String> params = new HashMap<>();
        if ("POST".equals(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            params = parseForm(body);
        }
        send(ex, 200, renderPage(params));
    }

    static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, val.trim());
        }
        return map;
    }

    // --------------------------------------------------------- логіка
    /** Рівномірно розбиває n питань на m модулів (залишок розподіляється по перших модулях). */
    static List<Module> splitIntoModules(int n, int m) {
        List<Module> list = new ArrayList<>();
        int base = n / m, rest = n % m, start = 1;
        for (int i = 0; i < m; i++) {
            int size = base + (i < rest ? 1 : 0);
            list.add(new Module(i + 1, start, size));
            start += size;
        }
        return list;
    }

    /** Одне число -> для всіх модулів; або m чисел через кому. */
    static int[] parseCounts(String s, int m) {
        String[] parts = s.split("[,;\\s]+");
        int[] k = new int[m];
        if (parts.length == 1) {
            Arrays.fill(k, toInt(parts[0], "Кількість питань з модуля"));
        } else if (parts.length == m) {
            for (int i = 0; i < m; i++) k[i] = toInt(parts[i], "Кількість питань з модуля");
        } else {
            throw new IllegalArgumentException(
                    "Вкажіть одне число (для всіх модулів) або рівно " + m + " чисел через кому.");
        }
        return k;
    }

    static int toInt(String s, String field) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Поле \"" + field + "\" має містити ціле число.");
        }
    }

    /** Біномінальний коефіцієнт C(n, k). */
    static BigInteger binom(int n, int k) {
        k = Math.min(k, n - k);
        BigInteger r = BigInteger.ONE;
        for (int i = 0; i < k; i++) {
            r = r.multiply(BigInteger.valueOf(n - i)).divide(BigInteger.valueOf(i + 1));
        }
        return r;
    }

    static List<Ticket> generate(int n, int m, int[] k, int count, List<Module> modules) {
        // --- перевірки
        if (n < 1) throw new IllegalArgumentException("Загальна кількість питань має бути ≥ 1.");
        if (m < 1 || m > n) throw new IllegalArgumentException("Кількість модулів має бути від 1 до " + n + ".");
        if (count < 1 || count > 500) throw new IllegalArgumentException("Кількість білетів: від 1 до 500.");

        BigInteger combinations = BigInteger.ONE;
        int perTicket = 0;
        for (int i = 0; i < m; i++) {
            int size = modules.get(i).size();
            if (k[i] < 0 || k[i] > size) {
                throw new IllegalArgumentException("У модулі " + (i + 1) + " лише " + size
                        + " питань - не можна взяти " + k[i] + ".");
            }
            combinations = combinations.multiply(binom(size, k[i]));
            perTicket += k[i];
        }
        if (perTicket == 0) throw new IllegalArgumentException("Білет не може бути порожнім.");
        if (BigInteger.valueOf(count).compareTo(combinations) > 0) {
            throw new IllegalArgumentException("Неможливо скласти " + count
                    + " унікальних білетів. Максимум для цих параметрів: " + combinations + ".");
        }

        // --- генерація унікальних білетів
        Random rnd = new Random();
        Set<List<Integer>> seen = new HashSet<>();
        List<Ticket> tickets = new ArrayList<>();
        int attempts = 0, maxAttempts = count * 500 + 5000;

        while (tickets.size() < count) {
            if (++attempts > maxAttempts) {
                throw new IllegalArgumentException(
                        "Не вдалося згенерувати стільки унікальних білетів. Зменште їх кількість.");
            }
            List<List<Integer>> parts = new ArrayList<>();
            List<Integer> flat = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                Module mod = modules.get(i);
                List<Integer> pool = new ArrayList<>();
                for (int j = 0; j < mod.size(); j++) pool.add(mod.from() + j);
                Collections.shuffle(pool, rnd);
                List<Integer> pick = new ArrayList<>(pool.subList(0, k[i]));
                Collections.sort(pick);
                parts.add(pick);
                flat.addAll(pick);
            }
            if (seen.add(flat)) {                 // білет ще не траплявся
                tickets.add(new Ticket(tickets.size() + 1, parts));
            }
        }
        return tickets;
    }

    // ------------------------------------------------------------ HTML
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String renderPage(Map<String, String> p) {
        String n = p.getOrDefault("n", "30");
        String m = p.getOrDefault("m", "3");
        String k = p.getOrDefault("k", "2");
        String t = p.getOrDefault("t", "5");

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="uk">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Генератор екзаменаційних білетів</title>
                <style>
                body{font-family:Arial,sans-serif;max-width:800px;margin:20px auto;padding:0 12px;color:#222}
                form{background:#f3f6fb;padding:16px;border-radius:8px}
                label{display:block;margin:10px 0 4px;font-weight:bold}
                input{width:100%;padding:8px;box-sizing:border-box;font-size:16px}
                small{color:#666}
                button{margin-top:14px;padding:10px 18px;font-size:16px;cursor:pointer}
                .err{background:#fde8e8;color:#a00;padding:10px;border-radius:6px;margin:14px 0}
                .info{background:#e8f5e9;padding:10px;border-radius:6px;margin:14px 0}
                .ticket{border:2px solid #333;border-radius:6px;padding:12px 16px;margin:14px 0;page-break-inside:avoid}
                .ticket h3{margin:0 0 8px}
                @media print{.no-print{display:none}}
                </style></head><body>
                <h1 class="no-print">Генератор екзаменаційних білетів</h1>
                <form method="post" class="no-print">
                """);
        sb.append("<label>Загальна кількість питань</label><input name='n' value='").append(esc(n)).append("'>");
        sb.append("<label>Кількість модулів (блоків)</label><input name='m' value='").append(esc(m)).append("'>");
        sb.append("<label>Кількість питань з кожного модуля у білеті</label><input name='k' value='")
                .append(esc(k)).append("'>")
                .append("<small>Одне число для всіх модулів або список через кому, напр.: 2,1,3</small>");
        sb.append("<label>Кількість білетів</label><input name='t' value='").append(esc(t)).append("'>");
        sb.append("<button type='submit'>Згенерувати</button></form>");

        if (!p.isEmpty()) {
            try {
                int nn = toInt(n, "Загальна кількість питань");
                int mm = toInt(m, "Кількість модулів");
                int tt = toInt(t, "Кількість білетів");
                if (mm < 1 || nn < mm) {
                    throw new IllegalArgumentException("Кількість модулів має бути від 1 до " + nn + ".");
                }
                List<Module> modules = splitIntoModules(nn, mm);
                int[] kk = parseCounts(k, mm);
                List<Ticket> tickets = generate(nn, mm, kk, tt, modules);

                sb.append("<div class='info'><b>Модулі:</b> ");
                for (Module mod : modules) {
                    sb.append("№").append(mod.number()).append(" (питання ")
                            .append(mod.from()).append("–").append(mod.from() + mod.size() - 1).append(") &nbsp; ");
                }
                sb.append("</div><button class='no-print' onclick='window.print()'>Друк</button>");

                for (Ticket tk : tickets) {
                    sb.append("<div class='ticket'><h3>Екзаменаційний білет №").append(tk.number()).append("</h3>");
                    for (int i = 0; i < tk.parts().size(); i++) {
                        List<Integer> part = tk.parts().get(i);
                        if (part.isEmpty()) continue;
                        sb.append("<div><b>Модуль ").append(i + 1).append(":</b> питання ");
                        StringJoiner sj = new StringJoiner(", ");
                        part.forEach(q -> sj.add("№" + q));
                        sb.append(sj).append("</div>");
                    }
                    sb.append("</div>");
                }
            } catch (IllegalArgumentException e) {
                sb.append("<div class='err'>").append(esc(e.getMessage())).append("</div>");
            }
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}
