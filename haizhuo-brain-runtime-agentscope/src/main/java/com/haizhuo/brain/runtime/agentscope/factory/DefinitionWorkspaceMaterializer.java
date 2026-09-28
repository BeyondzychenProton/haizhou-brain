package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * 把定义层工作区（规格 §26）物化到本地内容寻址目录 {@code <baseDir>/<bundleHash>/}，
 * 供 Harness 在构建模板时以只读方式挂载。P0 范围：编译后的工作区清单只引用
 * 承载员工指令的 AGENTS.md，skills/subagents/knowledge 列表均为空。
 */
public class DefinitionWorkspaceMaterializer {

    private final Path baseDir;

    public DefinitionWorkspaceMaterializer(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir);
    }

    public Path materialize(RuntimeDefinitionSnapshot definition) {
        Path directory = baseDir.resolve(definition.definitionBundleHash());
        Path agentsFile = directory.resolve("AGENTS.md");
        // 内容寻址：哈希相同即内容相同，已存在的文件可直接复用。
        if (Files.exists(agentsFile)) {
            return directory;
        }
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "AGENTS", ".tmp");
            Files.writeString(temp, definition.instructions(), StandardCharsets.UTF_8);
            Files.move(temp, agentsFile, StandardCopyOption.REPLACE_EXISTING);
            return directory;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to materialize definition workspace for bundle "
                    + definition.definitionBundleHash(), error);
        }
    }
}
