package org.aicancode.devblog.config;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.aicancode.devblog.ai.HttpLlmClient;
import org.aicancode.devblog.ai.LlmModerator;
import org.aicancode.devblog.ai.LlmSummarizer;
import org.aicancode.devblog.ai.ModerationService;
import org.aicancode.devblog.ai.RuleModerator;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.ai.TemplateSummarizer;
import org.aicancode.devblog.db.DatabaseUrl;
import org.aicancode.devblog.db.Migrator;
import org.aicancode.devblog.repo.CommentRepository;
import org.aicancode.devblog.repo.JdbcCommentRepository;
import org.aicancode.devblog.repo.JdbcPostRepository;
import org.aicancode.devblog.repo.JdbcUserRepository;
import org.aicancode.devblog.repo.PostRepository;
import org.aicancode.devblog.repo.UserRepository;
import org.aicancode.devblog.security.PasswordHasher;
import org.aicancode.devblog.security.TokenService;
import org.aicancode.devblog.service.AuthService;
import org.aicancode.devblog.service.CommentService;
import org.aicancode.devblog.service.PostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires real adapters. Unit tests build the same services by hand with in-memory adapters. */
@Configuration
public class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public DataSource dataSource(AppProperties props) {
        DatabaseUrl url = DatabaseUrl.parse(props.databaseUrl());
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url.jdbcUrl());
        ds.setUsername(url.username());
        ds.setPassword(url.password());
        ds.setMaximumPoolSize(10);
        ds.setConnectionTimeout(5000); // when the database is down a request fails after 5 s, not 30 s
        return ds;
    }

    @Bean
    public Migrator migrator(DataSource dataSource, AppProperties props) {
        Migrator migrator = new Migrator(dataSource, Path.of(props.migrationsDir()));
        migrator.migrate(); // fail fast: the app must not serve traffic against an old schema
        return migrator;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public UserRepository userRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcUserRepository(JdbcClient.create(dataSource));
    }

    @Bean
    public PostRepository postRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcPostRepository(JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Bean
    public CommentRepository commentRepository(DataSource dataSource, Migrator migrator) {
        return new JdbcCommentRepository(JdbcClient.create(dataSource));
    }

    @Bean
    public AuthService authService(UserRepository users, AppProperties props, Clock clock) {
        TokenService tokens = new TokenService(TokenService.resolveSecret(props.jwtSecret()), clock);
        AuthService auth = new AuthService(users, new PasswordHasher(props.bcryptCost()), tokens, clock);
        if (!props.adminEmail().isBlank() && !props.adminPassword().isBlank()) {
            auth.ensureAdmin(props.adminEmail(), props.adminPassword());
            LOG.info("admin account ensured for {}", props.adminEmail());
        }
        return auth;
    }

    @Bean
    public ModerationService moderationService(AppProperties props) {
        return new ModerationService(new RuleModerator(), props.llmEnabled() ? new LlmModerator(llm(props)) : null);
    }

    @Bean
    public SummaryService summaryService(AppProperties props) {
        return new SummaryService(new TemplateSummarizer(), props.llmEnabled() ? new LlmSummarizer(llm(props)) : null);
    }

    private static HttpLlmClient llm(AppProperties props) {
        return new HttpLlmClient(props.llmBaseUrl(), props.llmModel(), props.llmApiKey(), props.llmTimeoutMs());
    }

    @Bean
    public PostService postService(PostRepository posts, Clock clock, SummaryService summaries) {
        return new PostService(posts, clock, summaries);
    }

    @Bean
    public CommentService commentService(CommentRepository comments, PostRepository posts, ModerationService moderation,
                                         Clock clock) {
        return new CommentService(comments, posts, moderation, clock);
    }
}
