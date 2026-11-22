package cn.sgu.lingxun;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static cn.sgu.lingxun.Models.Viewer;
import static org.springframework.http.HttpStatus.*;

@Service
class SessionAccess {
    record SessionView(Viewer viewer, String csrf, boolean demo) {}
    private record Account(String name, String role, String passwordHash) {}

    static final String STUDENT = "student";
    static final String REVIEWER = "reviewer";

    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final boolean demo;

    SessionAccess(JdbcTemplate db, @Value("${lingxun.demo:false}") boolean demo) {
        this.db = db;
        this.demo = demo;
    }

    boolean demo() {
        return demo;
    }

    void provision(String username, String role, String password) {
        if (password.isBlank()) return;
        if (!validPassword(password)) throw new IllegalArgumentException("账户密码请控制在 72 个字节内");
        if (db.queryForObject("SELECT COUNT(*) FROM portal_account WHERE username=?", Integer.class, username) == 0) {
            db.update("INSERT INTO portal_account(username,user_role,password_hash) VALUES (?,?,?)",
                    username, role, encoder.encode(password));
        }
    }

    SessionView inspect(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? new SessionView(null, null, demo)
                : new SessionView((Viewer) session.getAttribute("viewer"), (String) session.getAttribute("csrf"), demo);
    }

    SessionView login(HttpServletRequest request, String username, String password) {
        if (username == null || username.length() > 48 || !validPassword(password)) throw invalidCredentials();
        Account account = db.query("SELECT * FROM portal_account WHERE username=?", (row, index) ->
                new Account(row.getString("username"), row.getString("user_role"), row.getString("password_hash")),
                username.strip()).stream().findFirst().orElseThrow(this::invalidCredentials);
        if (!encoder.matches(password, account.passwordHash())) throw invalidCredentials();
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute("viewer", new Viewer(account.name(), account.role()));
        session.setAttribute("csrf", UUID.randomUUID().toString());
        return inspect(request);
    }

    Viewer require(HttpServletRequest request, String role, boolean mutation) {
        SessionView session = inspect(request);
        if (session.viewer() == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        if (role != null && !role.equals(session.viewer().role())) {
            throw new ResponseStatusException(FORBIDDEN, "当前角色无权执行该操作");
        }
        if (mutation && !validToken(session.csrf(), request.getHeader("X-Lingxun-Token"))) {
            throw new ResponseStatusException(FORBIDDEN, "会话已变更，请刷新后再试");
        }
        return session.viewer();
    }

    private static boolean validToken(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean validPassword(String password) {
        return password != null && !password.isBlank() && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(UNAUTHORIZED, "账号或密码不正确");
    }
}
