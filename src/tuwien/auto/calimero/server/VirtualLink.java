/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2015 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.server;

import java.util.ArrayList;
import java.util.List;

import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalStateException;
import tuwien.auto.calimero.cemi.CEMIFactory;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.internal.EventListeners;
import tuwien.auto.calimero.link.AbstractLink;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.link.medium.TPSettings;

/**
 * A subnet link implementation used for virtual KNX networks. In such networks, the KNX
 * installation is realized by a network buffer or software KNX devices. The main purpose is to run
 * virtual KNX installations for development, testing, and visualization.
 *
 * @author B. Malinowsky
 */
public class VirtualLink extends AbstractLink
{
	private final List<VirtualLink> deviceLinks = new ArrayList<>();
	private final boolean isDeviceLink;

	public VirtualLink(final String name, final IndividualAddress endpoint)
	{
		this(name, endpoint, false);
	}

	private VirtualLink(final String name, final IndividualAddress endpoint,
		final boolean isDeviceLink)
	{
		super(name, new TPSettings(endpoint));
		this.isDeviceLink = isDeviceLink;
	}

	public KNXNetworkLink createDeviceLink(final IndividualAddress device)
	{
		// we could allow this in theory, but not really needed
		if (isDeviceLink)
			throw new KNXIllegalStateException("don't create device link from device link");

		final VirtualLink devLink = new VirtualLink("device " + device, device, true);
		devLink.deviceLinks.add(this);
		deviceLinks.add(devLink);
		return devLink;
	}

	@Override
	public String toString()
	{
		return getName() + " " + getKNXMedium().getDeviceAddress();
	}

	@Override
	protected void onSend(final KNXAddress dst, final byte[] msg, final boolean waitForCon)
		throws KNXLinkClosedException
	{}

	@Override
	protected void onSend(final CEMILData msg, final boolean waitForCon)
	{
		for (final VirtualLink l : deviceLinks)
			send(msg, notifier.getListeners(), l);
	}

	@Override
	protected void onClose()
	{}

	private void send(final CEMILData msg, final EventListeners<NetworkLinkListener> confirmation,
		final VirtualLink uplink)
	{
		// if the uplink is a device link:
		// we indicate all group destinations, and our device individual address,
		// filter out other individual addresses for device destination
		if (uplink.isDeviceLink) {
			if (msg.getDestination() instanceof GroupAddress)
				; // accept
			else if (!msg.getDestination().equals(uplink.getKNXMedium().getDeviceAddress()))
				return;
		}

		try {
			// send a .con for a .req
			if (msg.getMessageCode() == CEMILData.MC_LDATA_REQ) {
				final CEMILData f = (CEMILData) CEMIFactory.create(CEMILData.MC_LDATA_CON,
						msg.getPayload(), msg);
				final FrameEvent e = new FrameEvent(this, f);
				confirmation.fire(l -> l.confirmation(e));
			}
			// forward .ind as is, but convert req. to .ind
			final CEMILData f = msg.getMessageCode() == CEMILData.MC_LDATA_IND ? msg
					: (CEMILData) CEMIFactory.create(CEMILData.MC_LDATA_IND, msg.getPayload(), msg);
			final FrameEvent e = new FrameEvent(this, f);
			uplink.notifier.getListeners().fire(l -> l.indication(e));
		}
		catch (final KNXFormatException e) {
			logger.error("create cEMI for KNX link {} using: {}", uplink.getName(), msg, e);
		}
	}
}
