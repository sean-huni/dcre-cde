package za.co.fnb.dcre.cde.data.repo;

import org.springframework.jdbc.core.RowMapper;
import za.co.fnb.dcre.cde.data.model.ExcludedVerdict;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ExcludedVerdictMapper implements RowMapper<ExcludedVerdict> {

    @Override
    public ExcludedVerdict mapRow(ResultSet r, int rowNum) throws SQLException {
        return new ExcludedVerdict(r.getInt("sequence"), r.getString("e2e"), r.getString("outcome"));
    }
}
