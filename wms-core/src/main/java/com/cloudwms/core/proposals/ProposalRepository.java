package com.cloudwms.core.proposals;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cloudwms.core.proposals.ProposalService.NewProposal;
import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class ProposalRepository {

	private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
	};

	private static final TypeReference<List<String>> EVIDENCE = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;

	ProposalRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	long insert(NewProposal proposal, Actor actor) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO proposal (wave_id, kind, payload, rationale, evidence, created_by_type, created_by_id)
				VALUES (?, ?, ?, ?, ?, ?, ?)""")
			.params(proposal.waveId(), proposal.kind().name(), write(proposal.payload()), proposal.rationale(),
					write(proposal.evidence()), actor.type().name(), actor.id())
			.update(keys);
		return keys.getKey().longValue();
	}

	void decide(long id, String status, Actor actor, String note) {
		jdbc.sql("""
				UPDATE proposal
				SET status = ?, decided_by_type = ?, decided_by_id = ?, decided_at = CURRENT_TIMESTAMP(6),
				    decision_note = ?
				WHERE id = ? AND status = 'PROPOSED'""")
			.params(status, actor.type().name(), actor.id(), note, id)
			.update();
	}

	/** An open proposal of this kind for this task, if one is already waiting on a decision. */
	Optional<Long> openForTask(ProposalKind kind, long taskId) {
		return jdbc.sql("""
				SELECT id FROM proposal
				WHERE status = 'PROPOSED' AND kind = ? AND CAST(JSON_EXTRACT(payload, '$.task') AS UNSIGNED) = ?
				ORDER BY id LIMIT 1""")
			.params(kind.name(), taskId)
			.query(Long.class)
			.optional();
	}

	Optional<ProposalRow> find(long id) {
		return jdbc.sql(SELECT + "WHERE id = ?").param(id).query(this::map).optional();
	}

	List<ProposalRow> list(Long waveId, String status, long after, int limit) {
		return jdbc.sql(SELECT + """
				WHERE id > :after AND (:wave IS NULL OR wave_id = :wave) AND (:status IS NULL OR status = :status)
				ORDER BY id LIMIT :limit""")
			.param("after", after)
			.param("wave", waveId)
			.param("status", status)
			.param("limit", limit)
			.query(this::map)
			.list();
	}

	private static final String SELECT = """
			SELECT id, wave_id, kind, payload, rationale, evidence, status, created_by_type, created_by_id,
			       created_at, decided_by_type, decided_by_id, decided_at, decision_note
			FROM proposal
			""";

	private ProposalRow map(ResultSet rs, int rowNumber) throws SQLException {
		Long waveId = rs.getObject("wave_id", Long.class);
		String decidedByType = rs.getString("decided_by_type");
		return new ProposalRow(rs.getLong("id"), waveId, ProposalKind.valueOf(rs.getString("kind")),
				read(rs.getString("payload"), PAYLOAD), rs.getString("rationale"),
				read(rs.getString("evidence"), EVIDENCE), rs.getString("status"),
				new Actor(ActorType.valueOf(rs.getString("created_by_type")), rs.getString("created_by_id")),
				rs.getTimestamp("created_at").toInstant(),
				decidedByType == null ? null : new Actor(ActorType.valueOf(decidedByType), rs.getString("decided_by_id")),
				rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
				rs.getString("decision_note"));
	}

	private String write(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new DomainException(ErrorCode.VALIDATION_FAILED, "Proposal payload cannot be stored", Map.of());
		}
	}

	private <T> T read(String value, TypeReference<T> type) {
		return json.readValue(value, type);
	}

}
