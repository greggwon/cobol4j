/*
 * cobol4j - COBOL Runtime Semantics as a Java DSL
 * Copyright (C) 2026 Gregg Wonderly
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package org.cobol4j.cics;

/**
 * A self-describing CICS program that declares its own name and routing.
 * <p>
 * When discovered via ServiceLoader, the {@link ProgramLoader} automatically
 * installs the program under its declared name and wires up the transaction
 * route — no manual configuration needed in the region.
 * <p>
 * A JAR containing:
 * <pre>
 * META-INF/services/org.cobol4j.cics.CicsProgram
 *     com.bank.CustInquiry
 *     com.bank.CustUpdate
 *     com.bank.OrderEntry
 * </pre>
 * Where each class implements NamedCicsProgram:
 * <pre>{@code
 * public class CustInquiry implements NamedCicsProgram {
 *     @Override public String programName()    { return "CUSTINQ"; }
 *     @Override public String transactionId()  { return "CINQ"; }
 *
 *     @Override public void execute(CicsContext ctx) {
 *         ctx.receive(commarea);
 *         // ...
 *         ctx.send(commarea);
 *         ctx.returnTransaction();
 *     }
 * }
 * }</pre>
 * Drop the JAR in the deploy directory → program is installed and routed automatically.
 */
public interface NamedCicsProgram extends CicsProgram {

    /** The program name to install under (e.g., "CUSTINQ"). */
    String programName();

    /**
     * The transaction ID that routes to this program (e.g., "CINQ").
     * Return null if this program is only invoked via LINK/XCTL,
     * not directly by transaction ID.
     */
    default String transactionId() { return null; }

    /**
     * Optional description for monitoring/admin.
     */
    default String description() { return ""; }
}
