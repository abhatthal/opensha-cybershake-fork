package scratch.kevin.cybershake.simCompare;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.opensha.commons.data.Site;
import org.opensha.commons.data.function.DiscretizedFunc;
import org.opensha.commons.geo.Location;
import org.opensha.sha.imr.param.IntensityMeasureParams.DurationTimeInterval;
import org.opensha.sha.simulators.RSQSimEvent;
import org.opensha.sha.simulators.utils.RSQSimUtils;

import scratch.kevin.simCompare.SimulationRotDProvider;
import scratch.kevin.simulators.erf.RSQSimSectBundledERF;
import scratch.kevin.simulators.erf.RSQSimSectBundledERF.RSQSimProbEqkRup;
import scratch.kevin.simulators.ruptures.BBP_CatalogSimZipLoader;

public class CSRuptureBBPWrapperRotDProvider implements SimulationRotDProvider<CSRupture> {
	
	private RSQSimSectBundledERF erf;
	private Map<Integer, RSQSimEvent> events;
	private BBP_CatalogSimZipLoader bbpLoader;
	private Map<Site, List<CSRupture>> siteRups;

	public CSRuptureBBPWrapperRotDProvider(RSQSimSectBundledERF erf, Map<Integer, RSQSimEvent> events,
			BBP_CatalogSimZipLoader bbpLoader, Map<Site, List<CSRupture>> siteRups) {
		this.erf = erf;
		this.events = events;
		this.bbpLoader = bbpLoader;
		this.siteRups = siteRups;
	}

	@Override
	public String getName() {
		return bbpLoader.getName();
	}
	
	private RSQSimEvent getEvent(CSRupture rupture) {
		RSQSimProbEqkRup rup = erf.getRupture(rupture.getSourceID(), rupture.getRupID());
		return events.get(rup.getEventID());
	}

	@Override
	public DiscretizedFunc getRotD50(Site site, CSRupture rupture, int index) throws IOException {
		return bbpLoader.getRotD50(site, getEvent(rupture), index);
	}

	@Override
	public DiscretizedFunc getRotD100(Site site, CSRupture rupture, int index) throws IOException {
		return bbpLoader.getRotD100(site, getEvent(rupture), index);
	}

	@Override
	public DiscretizedFunc[] getRotD(Site site, CSRupture rupture, int index) throws IOException {
		return bbpLoader.getRotD(site, getEvent(rupture), index);
	}

	@Override
	public DiscretizedFunc getRotDRatio(Site site, CSRupture rupture, int index) throws IOException {
		return bbpLoader.getRotDRatio(site, getEvent(rupture), index);
	}

	@Override
	public double getPGV(Site site, CSRupture rupture, int index) throws IOException {
		return bbpLoader.getPGV(site, getEvent(rupture), index);
	}

	@Override
	public double getPGA(Site site, CSRupture rupture, int index) throws IOException {
		return bbpLoader.getPGA(site, getEvent(rupture), index);
	}

	@Override
	public double getDuration(Site site, CSRupture rupture, DurationTimeInterval interval, int index)
			throws IOException {
		return bbpLoader.getDuration(site, getEvent(rupture), interval, index);
	}

	@Override
	public int getNumSimulations(Site site, CSRupture rupture) {
		return bbpLoader.getNumSimulations(site, getEvent(rupture));
	}

	@Override
	public Collection<CSRupture> getRupturesForSite(Site site) {
		return siteRups.get(site);
	}

	@Override
	public boolean hasRotD50() {
		return bbpLoader.hasRotD50();
	}

	@Override
	public boolean hasRotD100() {
		return bbpLoader.hasRotD100();
	}

	@Override
	public boolean hasPGV() {
		return bbpLoader.hasPGV();
	}

	@Override
	public boolean hasPGA() {
		return bbpLoader.hasPGA();
	}

	@Override
	public boolean hasDurations() {
		return bbpLoader.hasDurations();
	}

	@Override
	public double getAnnualRate(CSRupture rupture) {
		return bbpLoader.getAnnualRate(getEvent(rupture));
	}

	@Override
	public double getMinimumCurvePlotRate(Site site) {
		return bbpLoader.getMinimumCurvePlotRate(site);
	}

	@Override
	public double getMagnitude(CSRupture rupture) {
		return getEvent(rupture).getMagnitude();
	}

	@Override
	public Location getHypocenter(CSRupture rupture, int index) {
		return RSQSimUtils.getHypocenter(getEvent(rupture));
	}

	@Override
	public double getRake(CSRupture rupture) {
		return RSQSimUtils.getElemAvgRake(getEvent(rupture), true);
	}

}
