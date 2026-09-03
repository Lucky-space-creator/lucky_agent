package com.lucky.agent.persona.center;

import com.lucky.agent.persona.api.BehaviorEngine;
import com.lucky.agent.persona.api.PersonaService;
import com.lucky.agent.persona.api.dto.BehaviorParam;
import com.lucky.agent.persona.api.dto.Persona;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 人格中心：预设 + 自定义，当前激活人格从 {@code <frameworkRoot>/.config/persona.json} 读取。
 */

@Slf4j
@Service
public class PersonaCenter implements PersonaService {

    
    private final List<Persona> presets;
    private final PersonaStore store;
    private final BehaviorEngine behaviorEngine;

    private volatile String activeId;
    private List<Persona> custom = new ArrayList<>();

    public PersonaCenter(PersonaStore store, BehaviorEngine behaviorEngine) {
        this.store = store;
        this.behaviorEngine = behaviorEngine;
        this.presets = defaultPresets();
        PersonaStore.PersonaState state = store.load();
        this.custom = state.custom() == null ? new ArrayList<>() : new ArrayList<>(state.custom());
        this.activeId = state.active() != null ? state.active() : presets.get(0).id();
    }

    @Override
    public Persona current() {
        return byId(activeId).orElse(presets.get(0));
    }

    @Override
    public List<Persona> presets() {
        return presets;
    }

    @Override
    public List<Persona> customPersonas() {
        return List.copyOf(custom);
    }

    @Override
    public synchronized Persona saveCustom(Persona persona) {
        Persona toSave = persona;
        if (toSave.id() == null || toSave.id().isBlank()) {
            toSave = Persona.custom(UUID.randomUUID().toString(), persona.name(),
                    persona.systemPrompt(), persona.tone(), persona.verbosity(),
                    persona.proactiveness(), persona.preferredSkills());
        }
        final Persona saved = toSave;
        custom.removeIf(p -> p.id().equals(saved.id()));
        custom.add(saved);
        persist();
        return saved;
    }

    @Override
    public synchronized Persona setActive(String personaId) {
        Persona target = byId(personaId)
                .orElseThrow(() -> new IllegalArgumentException("人格不存在：" + personaId));
        this.activeId = target.id();
        persist();
        log.info("已激活人格：{}", target.name());
        return target;
    }

    @Override
    public String renderPersonaLayer(String personaId) {
        Persona persona = byId(personaId).orElse(current());
        StringBuilder sb = new StringBuilder("【角色】你是" + persona.name() + "。");
        if (persona.systemPrompt() != null && !persona.systemPrompt().isBlank()) {
            sb.append('\n').append(persona.systemPrompt());
        }
        String behavior = behaviorEngine.render(BehaviorParam.of(persona));
        if (!behavior.isBlank()) {
            sb.append('\n').append(behavior);
        }
        return sb.toString();
    }

    private List<Persona> defaultPresets() {
        return List.of(
                Persona.preset("strict-engineer", "严谨工程师", "formal", 0.7, 0.6),
                Persona.preset("quick-assistant", "快速助手", "casual", 0.4, 0.7));
    }

    private Optional<Persona> byId(String personaId) {
        Map<String, Persona> all = new java.util.HashMap<>();
        for (Persona p : presets) {
            all.put(p.id(), p);
        }
        for (Persona p : custom) {
            all.put(p.id(), p);
        }
        return Optional.ofNullable(all.get(personaId));
    }

    private void persist() {
        store.save(new PersonaStore.PersonaState(activeId, custom));
    }
}
