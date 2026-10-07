package stabs.parse;

import stabs.model.CompileUnit;
import stabs.model.CrossRefType;
import stabs.model.TypeRef;

/** What the type parser needs from the surrounding stab parser. */
interface TypeContext {

	/** @return the slot for type number {@code (file,index)} in the current unit */
	TypeRef slot(int file, int index);

	CompileUnit unit();

	/** Called for every cross reference created, so it can be resolved later. */
	void crossRef(CrossRefType ref);
}
