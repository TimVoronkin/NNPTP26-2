package cz.upce.fei.nnptp.taskpulse.cli;

import cz.upce.fei.nnptp.taskpulse.domain.Project;
import cz.upce.fei.nnptp.taskpulse.domain.Task;
import cz.upce.fei.nnptp.taskpulse.domain.TaskState;
import cz.upce.fei.nnptp.taskpulse.service.TaskService;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryProjectRepository;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorklogCliHandlerTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

    private TaskService taskService;
    private WorklogCliHandler handler;
    private Task inProgressTask;

    @BeforeEach
    void setUp() {
        var projectRepository = new InMemoryProjectRepository();
        var taskRepository = new InMemoryTaskRepository();

        Project project = new Project("Backend API");
        projectRepository.save(project);

        Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Prague"));
        taskService = new TaskService(taskRepository, projectRepository, clock);

        inProgressTask = taskService.createTask(project.getId(), "Develop endpoints", "REST API task", null, null, null);
        taskService.transitionTask(inProgressTask.getId(), TaskState.IN_PROGRESS);

        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
        handler = new WorklogCliHandler(taskService, out, err);
    }

    @Test
    void handle_EmptyArguments_PrintsUsageToStderr() {
        // verify empty args show usage
        handler.handle(List.of());

        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("Usage: worklog <log|list> [arguments]");
    }

    @Test
    void handle_UnknownAction_PrintsUnknownActionToStderr() {
        handler.handle(List.of("unknown_cmd"));

        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("Unknown worklog action: 'unknown_cmd'");
    }

    @Test
    void handle_LogMissingArguments_PrintsUsageToStderr() {
        handler.handle(List.of("log", inProgressTask.getId().toString()));

        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("Usage: worklog log <task-id> <durationMinutes> --author <author> [--desc <description>]");
    }

    @Test
    void handle_LogInvalidDuration_ThrowsIllegalArgumentException() {
        // verify non-numeric duration throws exception
        assertThatThrownBy(() -> handler.handle(List.of("log", inProgressTask.getId().toString(), "invalid_number", "--author", "John")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration must be a valid integer number of minutes");
    }

    @Test
    void handle_LogMissingAuthorOption_PrintsMissingAuthorToStderr() {
        handler.handle(List.of("log", inProgressTask.getId().toString(), "45"));

        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("Missing required --author argument");
    }

    @Test
    void handle_LogValidWorklog_LogsWorkAndOutputsConfirmation() {
        // test logging work without description
        handler.handle(List.of("log", inProgressTask.getId().toString(), "45", "--author", "Alice"));

        String outMessage = output.toString(StandardCharsets.UTF_8);
        assertThat(outMessage).contains("Work logged: 45 min by Alice on task " + inProgressTask.getId());
        assertThat(taskService.getTask(inProgressTask.getId()).getWorklogs()).hasSize(1);
    }

    @Test
    void handle_LogWithDescription_LogsWorkAndOutputsConfirmation() {
        // test logging work with description
        handler.handle(List.of("log", inProgressTask.getId().toString(), "90", "--author", "Bob", "--desc", "Implementing auth"));

        String outMessage = output.toString(StandardCharsets.UTF_8);
        assertThat(outMessage).contains("Work logged: 90 min by Bob on task " + inProgressTask.getId());

        var worklogs = taskService.getTask(inProgressTask.getId()).getWorklogs();
        assertThat(worklogs).hasSize(1);
        assertThat(worklogs.get(0).description()).isEqualTo("Implementing auth");
    }

    @Test
    void handle_ListMissingTaskId_PrintsUsageToStderr() {
        handler.handle(List.of("list"));

        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("Usage: worklog list <task-id>");
    }

    @Test
    void handle_ListEmptyWorklogs_PrintsNoWorklogsMessage() {
        handler.handle(List.of("list", inProgressTask.getId().toString()));

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("No worklogs for task " + inProgressTask.getId());
    }

    @Test
    void handle_ListWithExistingWorklogs_PrintsFormattedWorklogs() {
        taskService.logWork(inProgressTask.getId(), 30, "Alice", "Setup database");
        taskService.logWork(inProgressTask.getId(), 60, "Bob", "");

        // verify listing outputs formatted entries
        handler.handle(List.of("list", inProgressTask.getId().toString()));

        String outMessage = output.toString(StandardCharsets.UTF_8);
        assertThat(outMessage)
                .contains("30 min | Alice | Setup database")
                .contains("60 min | Bob");
    }
}
